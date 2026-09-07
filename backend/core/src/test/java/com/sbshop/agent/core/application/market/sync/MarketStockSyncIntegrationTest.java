package com.sbshop.agent.core.application.market.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.domain.product.edit.*;
import com.sbshop.agent.core.domain.product.service.SalePriceRounding;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.MarketStockRead;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@DataJpaTest(showSql = false, properties = {
	"spring.datasource.url=jdbc:h2:mem:stocksync;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketStockSyncIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketStockSyncIntegrationTest {
	@org.springframework.test.context.DynamicPropertySource
	static void isolatedPostgres(org.springframework.test.context.DynamicPropertyRegistry registry) {
		String url = System.getenv("SBSHOP_STOCK_TEST_POSTGRES_URL");
		if (url == null)
			return;
		if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sbshop_stock_check"))
			throw new IllegalArgumentException("Only the local isolated stock test database is allowed");
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@SpringBootApplication
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketStockTaskRepository.class, MarketInspectionGateRepository.class})
	@Import({MarketStockSyncService.class, MarketPriceSyncService.class, ProductEditService.class,
		ProductEditPlanner.class, ProductEditPolicy.class})
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}

	@Autowired
	MarketStockSyncService service;
	@Autowired
	ProductEditService edits;
	@Autowired
	MarketPriceSyncService priceSync;
	@Autowired
	ProductChangeTargetRepository changeTargets;
	@Autowired
	ProductChangeHistoryRepository changeHistories;
	@Autowired
	ProductEditReviewRepository editReviews;
	@Autowired
	ObjectMapper mapper;
	@Autowired
	MarketPriceTaskRepository priceTasks;
	@Autowired
	MarketPriceAttemptRepository priceAttempts;
	@Autowired
	MarketPriceReviewRepository priceReviews;
	@MockitoBean
	MarketSalePriceResolver prices;
	@Autowired
	MarketStockTaskRepository tasks;
	@Autowired
	MarketStockReviewRepository reviews;
	@Autowired
	MarketStockAttemptRepository attempts;
	@Autowired
	MarketInspectionGateRepository gates;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactions;
	@MockitoBean
	MarketClientRouter clients;
	MarketClient client;
	Product product;
	MarketRegistration reg;
	static final MarketType MARKET = MarketType.COUPANG;

	@BeforeEach
	void setup() {
		changeTargets.deleteAll();
		changeHistories.deleteAll();
		editReviews.deleteAll();
		priceAttempts.deleteAll();
		priceTasks.deleteAll();
		priceReviews.deleteAll();
		attempts.deleteAll();
		tasks.deleteAll();
		reviews.deleteAll();
		gates.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		client = mock(MarketClient.class);
		when(clients.hasClient(MARKET)).thenReturn(true);
		when(clients.getClient(MARKET)).thenReturn(client);
		when(client.inspectionAccountReference()).thenReturn("account-A");
		when(prices.explainForProduct(any(), any(), any())).thenReturn(new MarketSalePriceResolver.Explanation(
			MarketSalePriceResolver.Basis.CALCULATED,
			SalePriceRounding.fromPrice(new BigDecimal("12300"), new BigDecimal("10000"))));
		doAnswer(call -> {
			call.<Runnable>getArgument(5).run();
			return null;
		}).when(client)
			.writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		product = product();
		reg = registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId()).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"123\",\"vendorItemId\":\"456\"}").build());
	}

	Product product() {
		return products.saveAndFlush(Product.create("SB-" + UUID.randomUUID(), new ProductCreateCommand(
			"https://example.com/item", new BigDecimal("10000"), "상품", "original", "브랜드", "US", new BigDecimal("0.3"),
			new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "html", "FOOD", true, 1, new BigDecimal("20"),
			VendorType.IHB, null)));
	}

	MarketStockSyncService.Review preview() {
		return service.preview(List.of(product.getId()), Set.of(MARKET), "admin");
	}

	MarketStockSyncService.Review queue() {
		return service.commit(preview().id(), "admin");
	}

	MarketStockRead read(int quantity) {
		return new MarketStockRead(quantity, true, "fixture", "account-A", "456");
	}

	void observed(int quantity){when(client.readStockQuantity("123","456",product.getSbCode())).thenReturn(read(quantity));}

	void release() {
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
		jdbc.update("update sb_market_stock_task set next_run_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
	}

	String state(String id) {
		return service.get(id, "admin").items().getFirst().state();
	}

	@Test
	void defaultSalesQuantityIs300AndNeverSourceStockCount() {
		jdbc.update("update sb_product set stock=987 where id=?", product.getId());
		assertThat(preview().items().getFirst().expectedQuantity()).isEqualTo(300);
		jdbc.update("update sb_product set stock_status='OUT_OF_STOCK' where id=?", product.getId());
		assertThat(preview().items().getFirst().expectedQuantity()).isZero();
	}

	@Test
	void salesQuantityAboveDbEditLimitIsSkipped() {
		jdbc.update("update sb_product set sales_quantity=1000000 where id=?", product.getId());
		assertThat(preview().items().getFirst().state()).isEqualTo("SKIPPED");
	}

	@Test
	void explicitWriteRejectionIsReadBackButNeverAutomaticallyWrittenAgain() {
		observed(10);
		var r = queue();
		doAnswer(call -> {
			call.<Runnable>getArgument(5).run();
			throw new MarketTransferFailure("HTTP_403", "permission", null, null);
		})
			.when(client).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("VERIFY");
		release();
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("BLOCKED");
		assertThat(tasks.findByReviewIdOrderById(r.id()).getFirst().getWrites()).isEqualTo(1);
	}

	@Test
	void explicitReadRejectionDoesNotRetryWritesOrInventDeletion() {
		var r = queue();
		when(client.readStockQuantity(any(), any(), any()))
			.thenThrow(new MarketTransferFailure("HTTP_404", "missing", null, null));
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("BLOCKED");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState().detached()).isFalse();
		verify(client, never()).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
	}

	@Test
	void sourceGoneUnknownStatusAndCrawlFailureAreSkippedWithoutTurningIntoZero() {
		jdbc.update("update sb_product set stock_status=null where id=?", product.getId());
		assertThat(preview().items().getFirst().state()).isEqualTo("SKIPPED");
		jdbc.update("update sb_product set stock_status='OUT_OF_STOCK',last_crawl_error='timeout' where id=?",
			product.getId());
		assertThat(preview().items().getFirst().expectedQuantity()).isNull();
		jdbc.update("update sb_product set last_crawl_error=null,source_gone_at=CURRENT_TIMESTAMP where id=?",
			product.getId());
		assertThat(preview().items().getFirst().state()).isEqualTo("SKIPPED");
	}

	@Test
	void reviewAndHistoryAreActorBoundAndCommitIsIdempotent() {
		var r = queue();
		assertThat(service.commit(r.id(), "admin").items()).hasSize(1);
		assertThat(tasks.count()).isEqualTo(1);
		assertThatThrownBy(() -> service.get(r.id(), "other")).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> service.commit(r.id(), "other")).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> service.history(r.items().getFirst().id(), "other"))
			.isInstanceOf(RuntimeException.class);
		assertThat(service.recent("other")).isEmpty();
		assertThat(queue().items().getFirst().state()).isEqualTo("SKIPPED");
	}

	@Test
	void expiredReviewAndChangedRevisionCannotEnqueueWrites() {
		var a = preview();
		jdbc.update("update sb_market_stock_review set expires_at=? where id=?", java.sql.Timestamp.from(Instant.EPOCH),
			a.id());
		assertThatThrownBy(() -> service.commit(a.id(), "admin")).isInstanceOf(RuntimeException.class);
		assertThat(tasks.count()).isZero();
		var b = preview();
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		assertThat(service.commit(b.id(), "admin").items().getFirst().state()).isEqualTo("SKIPPED");
	}

	@Test
	void equalQuantityConfirmsOnlyQuantityWithoutWritingOrMarkingWholeProductSynced() {
		observed(300);
		var r = queue();
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("CONFIRMED_QUANTITY");
		verify(client, never()).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		assertThat(registrations.findById(reg.getId()).orElseThrow().getIsSynced()).isNotEqualTo(true);
	}

	@Test
	void timeoutAfterAppliedWriteIsReadBeforeRetryAndConfirmedWithoutDuplicateWrite() {
		observed(10);
		var r = queue();
		doAnswer(call -> {
			call.<Runnable>getArgument(5).run();
			observed(300);
			throw new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null);
		})
			.when(client).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("VERIFY");
		release();
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("CONFIRMED_QUANTITY");
		verify(client, times(1)).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		assertThat(service.history(r.items().getFirst().id(), "admin")).extracting(MarketStockAttempt::getPhase)
			.contains("WRITE_STARTED", "VERIFY", "CONFIRMED_QUANTITY");
	}

	@Test
	void successfulReceiptRemainsPendingAndRepeatedMismatchBecomesExplicitFailure() {
		observed(10);
		var r = queue();
		for (int i = 0; i < 4; i++) {
			release();
			service.processOne(MARKET);
		}
		assertThat(state(r.id())).isEqualTo("FAILED_MISMATCH");
		assertThat(tasks.findByReviewIdOrderById(r.id()).getFirst().getWrites()).isEqualTo(3);
	}

	@Test void stoppedEvenIfQuantityMatchesIsBlockedAndNeverResumed() {
        when(client.readStockQuantity(any(),any(),any())).thenReturn(new MarketStockRead(300,false,"판매금지","account-A","456"));
        var r=queue();service.processOne(MARKET);
        assertThat(state(r.id())).isEqualTo("BLOCKED");
        verify(client,never()).writeStockQuantity(any(),any(),any(),anyInt(),any(),any());
        verify(client,never()).syncPriceAndStock(any(),any(),any(),anyInt(),anyBoolean());
    }

	@Test
	void nullQuantityOrWrongAccountIsNeverSuccessOrImplicitZero() {
		var r = queue();
		when(client.readStockQuantity(any(), any(), any()))
			.thenReturn(new MarketStockRead(null, true, "missing", "account-A", "456"));
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("UNKNOWN");
		var b = queue();
		when(client.readStockQuantity(any(), any(), any()))
			.thenReturn(new MarketStockRead(300, true, "wrong", "account-B", "456"));
		release();
		service.processOne(MARKET);
		assertThat(state(b.id())).isEqualTo("UNKNOWN");
		verify(client, never()).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
	}

	@Test
	void revisionAndConnectionChangesDuringRemoteReadStopTheWrite() {
		var r = queue();
		when(client.readStockQuantity(any(), any(), any())).thenAnswer(call -> {
			jdbc.update("update sb_market_registration set revision=revision+1 where id=?", reg.getId());
			return read(10);
		});
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("STALE");
		assertThat(tasks.findByReviewIdOrderById(r.id()).getFirst().getWrites()).isZero();
	}

	@Test
	void productChangeDuringAdapterRevalidationStopsPutAtLastGuard() {
		observed(10);
		var r = queue();
		var put = new java.util.concurrent.atomic.AtomicBoolean();
		doAnswer(call -> {
			jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
			call.<Runnable>getArgument(5).run();
			put.set(true);
			return null;
		})
			.when(client).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("STALE");
		assertThat(put).isFalse();
	}

	@Test
	void expiredLeaseCannotWriteOrConfirmAndLate429PreservesNewerLeaseAndCooldown() {
		var r = queue();
		var old = service.claim(MARKET);
		release();
		var newer = service.claim(MARKET);
		assertThat(service.beginWrite(old, read(10))).isFalse();
		service.finish(old, "CONFIRMED_QUANTITY", "old", read(300), null);
		assertThat(state(r.id())).isEqualTo("CHECK");
		Instant until = Instant.now().plusSeconds(900);
		service.finish(old, "VERIFY", "late429", null, new MarketTransferFailure("HTTP_429", "limit", until, null));
		var gate = gates.findById(MARKET + "_ORIGIN_READ").orElseThrow();
		assertThat(gate.getLeaseToken()).isEqualTo(newer.token());
		assertThat(gate.getNextAllowedAt()).isAfterOrEqualTo(until);
		assertThat(service.beginWrite(newer, read(10))).isFalse();
		assertThat(tasks.findByReviewIdOrderById(r.id()).getFirst().getWrites()).isZero();
		assertThat(gates.findById(MARKET + "_ORIGIN_READ").orElseThrow().getNextAllowedAt()).isAfterOrEqualTo(until);
		assertThat(service.claim(MARKET)).isNull();
	}

	@Test
	void server429RetryAfterDelaysSharedGateAndTask() {
		var r = queue();
		Instant until = Instant.now().plusSeconds(900);
		when(client.readStockQuantity(any(), any(), any()))
			.thenThrow(new MarketTransferFailure("HTTP_429", "limit", until, null));
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("VERIFY");
		assertThat(tasks.findByReviewIdOrderById(r.id()).getFirst().getNextRunAt()).isAfterOrEqualTo(until);
		assertThat(service.claim(MARKET)).isNull();
	}

	@Test
	void transactionIsSuspendedForRemoteReadsAndPutsEvenWhenCallerHasTransaction() {
		var r = queue();
		when(client.readStockQuantity(any(), any(), any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return read(10);
		});
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			call.<Runnable>getArgument(5).run();
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return null;
		}).when(client).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		new TransactionTemplate(transactions).executeWithoutResult(s -> service.processOne(MARKET));
		assertThat(state(r.id())).isEqualTo("VERIFY");
	}

	@Test
	void queueInsertFailureRollsBackEarlierTasksAndReviewCommitTogether() {
		Product second = product();
		registrations.saveAndFlush(MarketRegistration.builder().productId(second.getId()).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"789\",\"vendorItemId\":\"987\"}").build());
		var r = service.preview(List.of(product.getId(), second.getId()), Set.of(MARKET), "admin");
		jdbc.execute("alter table sb_market_stock_task add constraint stock_test_reject check (product_id <> "
			+ second.getId() + ")");
		try {
			assertThatThrownBy(() -> service.commit(r.id(), "admin")).isInstanceOf(RuntimeException.class);
			assertThat(tasks.count()).isZero();
			assertThat(reviews.findById(r.id()).orElseThrow().getCommittedAt()).isNull();
		} finally {
			jdbc.execute("alter table sb_market_stock_task drop constraint stock_test_reject");
		}
		assertThat(service.commit(r.id(), "admin").items()).hasSize(2);
	}

	@Test
	void realProductRowLockSerializesTwoReviewCommitsWithoutDuplicateActiveTasks() throws Exception {
		var a = preview();
		var b = preview();
		var held = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var entered = new CountDownLatch(2);
		try (var pool = Executors.newFixedThreadPool(3)) {
			Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
				products.findForEdit(product.getId()).orElseThrow();
				held.countDown();
				try {
					assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}));
			assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
			Future<?> first = pool.submit(() -> {
				entered.countDown();
				return service.commit(a.id(), "admin");
			});
			Future<?> second = pool.submit(() -> {
				entered.countDown();
				return service.commit(b.id(), "admin");
			});
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> first.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			} finally {
				release.countDown();
			}
			holder.get(5, TimeUnit.SECONDS);
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
		}
		assertThat(tasks.findAll()).extracting(MarketStockTask::getState).containsExactlyInAnyOrder("CHECK", "SKIPPED");
	}

	ProductChangeTarget savedQuantity(int quantity) {
		var current = products.findById(product.getId()).orElseThrow();
		var review = edits.previewSingle(current.getId(), current.getRevision(),
			mapper.createObjectNode().put("salesQuantity", quantity), "admin");
		assertThat(review.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.READY);
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		return changeTargets.findAll().getLast();
	}

	@Test
	void reviewedCoupangQuantitySaveAutomaticallyDispatchesAndConfirmsOnlyItsQuantityTarget() {
		int sourceStock = product.getStock();
		var target = savedQuantity(450);
		observed(450);
		priceSync.dispatchSavedPrices();
		assertThat(changeTargets.findById(target.getId()).orElseThrow().getState()).isEqualTo("PENDING_DISPATCH");
		service.dispatchSavedQuantities();
		service.dispatchSavedQuantities();
		assertThat(tasks.count()).isEqualTo(1);
		assertThat(priceTasks.count()).isZero();
		var dispatched = changeTargets.findById(target.getId()).orElseThrow();
		assertThat(dispatched.getState()).isEqualTo("DISPATCHED");
		assertThat(dispatched.getStockTaskId()).isNotNull();
		assertThat(dispatched.getPriceTaskId()).isNull();
		service.processOne(MARKET);
		assertThat(changeTargets.findById(target.getId()).orElseThrow().getState()).isEqualTo("CONFIRMED_QUANTITY");
		assertThat(service.recent("admin")).hasSize(1);
		assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(sourceStock);
		assertThat(registrations.findById(reg.getId()).orElseThrow().getIsSynced()).isNotEqualTo(true);
	}

	@Test
	void quantityAndPriceTargetsCoexistWithoutClaimingTheOtherFieldsSuccess() {
		var quantity = savedQuantity(450);
		var history = changeHistories.findById(quantity.getHistoryId()).orElseThrow();
		var price = changeTargets.saveAndFlush(new ProductChangeTarget(history.getId(), product.getId(), reg.getId(),
			quantity.getProductRevision(), MARKET.name(), "{\"changes\":[{\"field\":\"salePrice\"}]}"));
		priceSync.dispatchSavedPrices();
		service.dispatchSavedQuantities();
		assertThat(priceTasks.count()).isEqualTo(1);
		assertThat(tasks.count()).isEqualTo(1);
		observed(450);
		service.processOne(MARKET);
		assertThat(changeTargets.findById(quantity.getId()).orElseThrow().getState()).isEqualTo("CONFIRMED_QUANTITY");
		assertThat(changeTargets.findById(price.getId()).orElseThrow().getState()).isEqualTo("DISPATCHED");
	}

	@Test
	void oldSavedRevisionNeverSendsCurrentQuantityWithoutItsOwnReviewedTarget() {
		var target = savedQuantity(450);
		jdbc.update("update sb_product set revision=revision+1,sales_quantity=700 where id=?", product.getId());
		service.dispatchSavedQuantities();
		assertThat(tasks.count()).isZero();
		assertThat(changeTargets.findById(target.getId()).orElseThrow().getState()).isEqualTo("ACTION_REQUIRED");
	}

	@Test
	void newerSavedQuantityWaitsForOldTaskToBecomeStaleThenUsesCurrentRevision() {
		var old = savedQuantity(450);
		service.dispatchSavedQuantities();
		var current = savedQuantity(600);
		service.dispatchSavedQuantities();
		assertThat(tasks.count()).isEqualTo(1);
		service.processOne(MARKET);
		assertThat(changeTargets.findById(old.getId()).orElseThrow().getState()).isEqualTo("ACTION_REQUIRED");
		service.dispatchSavedQuantities();
		observed(600);
		service.processOne(MARKET);
		assertThat(changeTargets.findById(current.getId()).orElseThrow().getState()).isEqualTo("CONFIRMED_QUANTITY");
		assertThat(changeTargets.findById(old.getId()).orElseThrow().getState()).isEqualTo("SUPERSEDED_BY_CURRENT");
	}

	@Test
	void savedQuantityWithSourceFailureOrStoppedListingRemainsActionRequired() {
		var first = savedQuantity(450);
		jdbc.update("update sb_product set last_crawl_error='timeout' where id=?", product.getId());
		service.dispatchSavedQuantities();
		assertThat(changeTargets.findById(first.getId()).orElseThrow().getState()).isEqualTo("ACTION_REQUIRED");
		assertThat(service.recent("admin")).hasSize(1);
		jdbc.update("update sb_product set last_crawl_error=null where id=?", product.getId());
		var second = savedQuantity(500);
		service.dispatchSavedQuantities();
		when(client.readStockQuantity(any(), any(), any()))
			.thenReturn(new MarketStockRead(10, false, "정지", "account-A", "456"));
		service.processOne(MARKET);
		assertThat(changeTargets.findById(second.getId()).orElseThrow().getState()).isEqualTo("ACTION_REQUIRED");
	}

	@Test
	void allOtherConnectionsAndUnconfirmedOptionsKeepQuantityLocked() {
		var policy = new ProductEditPolicy();
		assertThat(policy.rule("salesQuantity", List.of(reg)).editable()).isTrue();
		assertThat(policy.rule("stock", List.of(reg)).editable()).isFalse();
		var unknown = MarketRegistration.builder().productId(product.getId()).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"123\"}").build();
		assertThat(policy.rule("salesQuantity", List.of(unknown)).editable()).isFalse();
		var cafe = MarketRegistration.builder().productId(product.getId()).marketType(MarketType.CAFE24)
			.marketIdentifiers("{\"product_no\":\"123\"}").build();
		assertThat(policy.rule("salesQuantity", List.of(reg, cafe)).editable()).isFalse();
		assertThat(policy.rule("salesQuantity", List.of(cafe)).editable()).isFalse();
	}

	@Test
	void savedQuantityDoesNotSilentlyMoveToNewListingBeforeDispatch() {
		var target = savedQuantity(450);
		jdbc.update("update sb_market_registration set market_identifiers=? where id=?",
			"{\"sellerProductId\":\"789\",\"vendorItemId\":\"987\"}", reg.getId());
		service.dispatchSavedQuantities();
		assertThat(changeTargets.findById(target.getId()).orElseThrow().getState()).isEqualTo("ACTION_REQUIRED");
		assertThat(tasks.findAll()).extracting(MarketStockTask::getState).containsExactly("SKIPPED");
		service.processOne(MARKET);
		verify(client, never()).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
	}

	void smartstoreRegistration() {
		registrations.deleteAll();
		reg = registrations
			.saveAndFlush(MarketRegistration.builder().productId(product.getId()).marketType(MarketType.SMART_STORE)
				.marketIdentifiers("{\"originProductNo\":\"123\"}").build());
		when(clients.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(clients.getClient(MarketType.SMART_STORE)).thenReturn(client);
	}

	@Test
	void smartstoreQuantityQueueConfirmsOnlyExactOriginQuantityAndKeepsSourceStockSeparate() {
		smartstoreRegistration();
		var r = service.preview(List.of(product.getId()), Set.of(MarketType.SMART_STORE), "admin");
		assertThat(r.items().getFirst().expectedQuantity()).isEqualTo(300);
		service.commit(r.id(), "admin");
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenReturn(new MarketStockRead(300, true, "origin", "account-A", "ORIGIN:123"));
		service.processOne(MarketType.SMART_STORE);
		assertThat(state(r.id())).isEqualTo("CONFIRMED_QUANTITY");
		verify(client, never()).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		assertThat(registrations.findById(reg.getId()).orElseThrow().getIsSynced()).isNotEqualTo(true);
	}

	@Test
	void smartstoreUncertainWriteReusesFrozenOptionIdentityAndReadsBeforeResending() {
		smartstoreRegistration();
		var r = service.preview(List.of(product.getId()), Set.of(MarketType.SMART_STORE), "admin");
		service.commit(r.id(), "admin");
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenReturn(new MarketStockRead(10, true, "option", "account-A", "COMBINATION:456"));
		when(client.readStockQuantity("123", "COMBINATION:456", product.getSbCode()))
			.thenReturn(new MarketStockRead(300, true, "option", "account-A", "COMBINATION:456"));
		doAnswer(call -> {
			call.<Runnable>getArgument(5).run();
			throw new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null);
		})
			.when(client).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		service.processOne(MarketType.SMART_STORE);
		assertThat(state(r.id())).isEqualTo("VERIFY");
		release();
		service.processOne(MarketType.SMART_STORE);
		assertThat(state(r.id())).isEqualTo("CONFIRMED_QUANTITY");
		verify(client, times(1)).writeStockQuantity(eq("123"), eq("COMBINATION:456"), eq(product.getSbCode()), eq(300),
			eq("account-A"), any());
		verify(client).readStockQuantity("123", "COMBINATION:456", product.getSbCode());
	}

	@Test
	void smartstore429BlocksTheExistingSmartstorePriceLaneToo() {
		smartstoreRegistration();
		var stock = service.preview(List.of(product.getId()), Set.of(MarketType.SMART_STORE), "admin");
		service.commit(stock.id(), "admin");
		var price = priceSync.preview(List.of(product.getId()), Set.of(MarketType.SMART_STORE), "admin");
		priceSync.commit(price.id(), "admin");
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenThrow(new MarketTransferFailure("HTTP_429", "limit", Instant.now().plusSeconds(900), null));
		service.processOne(MarketType.SMART_STORE);
		assertThat(priceSync.claim(MarketType.SMART_STORE)).isNull();
		assertThat(state(stock.id())).isEqualTo("VERIFY");
	}

	@Test
	void smartstoreOptionIdentityChangeCannotConfirmOrWriteToAnotherOption() {
		smartstoreRegistration();
		var r = service.preview(List.of(product.getId()), Set.of(MarketType.SMART_STORE), "admin");
		service.commit(r.id(), "admin");
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenReturn(new MarketStockRead(10, true, "option", "account-A", "COMBINATION:456"));
		service.processOne(MarketType.SMART_STORE);
		release();
		when(client.readStockQuantity("123", "COMBINATION:456", product.getSbCode()))
			.thenReturn(new MarketStockRead(300, true, "changed", "account-A", "STANDARD:456"));
		service.processOne(MarketType.SMART_STORE);
		assertThat(state(r.id())).isEqualTo("UNKNOWN");
		assertThat(tasks.findByReviewIdOrderById(r.id()).getFirst().getWrites()).isEqualTo(1);
	}

}
