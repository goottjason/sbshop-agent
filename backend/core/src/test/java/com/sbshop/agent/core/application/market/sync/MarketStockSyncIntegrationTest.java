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
		return new MarketStockRead(quantity, true, "fixture", "account-A", "456", "103", "01");
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

	@Test
	void cafe24SameQuantityMustStillResumeSellingAndVerifyIt() {
		registrations.deleteAll();
		reg = registrations
			.saveAndFlush(MarketRegistration.builder().productId(product.getId()).marketType(MarketType.CAFE24)
				.marketIdentifiers("{\"product_no\":\"123\"}").build());
		when(clients.hasClient(MarketType.CAFE24)).thenReturn(true);
		when(clients.getClient(MarketType.CAFE24)).thenReturn(client);
		var r = service.preview(List.of(product.getId()), Set.of(MarketType.CAFE24), "admin");
		service.commit(r.id(), "admin");
		when(client.readStockQuantity(any(), any(), any()))
			.thenReturn(new MarketStockRead(300, true, "cafe", "account-A", "P0000001000A", "F", "T"));
		service.processOne(MarketType.CAFE24);
		assertThat(state(r.id())).isEqualTo("VERIFY");
		verify(client).writeStockQuantity(eq("123"), eq("P0000001000A"), eq(product.getSbCode()), eq(300),
			eq("account-A"), any());
		release();
		when(client.readStockQuantity(any(), any(), any()))
			.thenReturn(new MarketStockRead(300, true, "cafe", "account-A", "P0000001000A", "T", "T"));
		service.processOne(MarketType.CAFE24);
		assertThat(state(r.id())).isEqualTo("CONFIRMED_QUANTITY");
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

	void elevenstRegistration() {
		registrations.deleteAll();
		reg = registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId())
			.marketType(MarketType.ELEVEN_STREET)
			.marketIdentifiers("{\"elevenstId\":\"123\",\"variant_code\":\"unrelated-cafe24-value\"}").build());
		when(clients.hasClient(MarketType.ELEVEN_STREET)).thenReturn(true);
		when(clients.getClient(MarketType.ELEVEN_STREET)).thenReturn(client);
	}

	MarketStockSyncService.Review elevenstQueue() {
		return service.commit(service.preview(List.of(product.getId()), Set.of(MarketType.ELEVEN_STREET), "admin")
			.id(), "admin");
	}

	@Test
	void elevenstDedicatedInventoryReadResolvesStockIdAndRequiresReadbackAfterWrite() {
		elevenstRegistration();
		var review = elevenstQueue();
		when(client.readStockQuantity("123", null, product.getSbCode())).thenReturn(read(17));
		when(client.readStockQuantity("123", "456", product.getSbCode())).thenReturn(read(300));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("VERIFY");
		assertThat(tasks.findByReviewIdOrderById(review.id()).getFirst().getResolvedOptionId()).isEqualTo("456");
		release();
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("CONFIRMED_QUANTITY");
		verify(client, times(1)).writeStockQuantity(eq("123"), eq("456"), eq(product.getSbCode()), eq(300),
			eq("account-A"), any());
		verify(client, never()).readStockQuantity("123", "unrelated-cafe24-value", product.getSbCode());
		assertThat(registrations.findById(reg.getId()).orElseThrow().getIsSynced()).isNotEqualTo(true);
	}

	@Test
	void elevenstZeroRequiresBothRawZeroAndIndependentUnavailableStateBeforeConfirmation() {
		elevenstRegistration();
		jdbc.update("update sb_product set stock_status='OUT_OF_STOCK' where id=?", product.getId());
		var review = elevenstQueue();
		when(client.readStockQuantity("123", null, product.getSbCode())).thenReturn(read(300));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("VERIFY");
		var task = tasks.findByReviewIdOrderById(review.id()).getFirst();
		assertThat(task.getObservedQuantity()).isEqualTo(300);
		assertThat(task.getObservedSaleState()).isEqualTo("103");
		verify(client).writeStockQuantity(eq("123"), eq("456"), eq(product.getSbCode()), eq(0), eq("account-A"), any());
		release();
		when(client.readStockQuantity("123", "456", product.getSbCode()))
			.thenReturn(new MarketStockRead(0, true, "품절", "account-A", "456", "104", "02"));
		service.processOne(MarketType.ELEVEN_STREET);
		var item = service.get(review.id(), "admin").items().getFirst();
		assertThat(item.state()).isEqualTo("CONFIRMED_QUANTITY");
		assertThat(item.observedQuantity()).isZero();
		assertThat(item.observedSaleState()).isEqualTo("104");
		assertThat(item.observedStockState()).isEqualTo("02");
		assertThat(item.detail()).contains("품절(104)");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState().detached()).isFalse();
	}

	@Test
	void elevenstSoldOutCanResumeOnlyAfterMatchingRawQuantityAndSellingStateReadback() {
		elevenstRegistration();
		var review = elevenstQueue();
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenReturn(new MarketStockRead(0, true, "품절", "account-A", "456", "104", "02"));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("VERIFY");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState().detached()).isFalse();
		release();
		when(client.readStockQuantity("123", "456", product.getSbCode())).thenReturn(read(300));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("CONFIRMED_QUANTITY");
		verify(client).writeStockQuantity(eq("123"), eq("456"), eq(product.getSbCode()), eq(300), eq("account-A"),
			any());
	}

	@Test
	void elevenstBusiness500IsNotHttp500AndCannotTriggerAnotherWriteAfterReadback() {
		elevenstRegistration();
		var review = elevenstQueue();
		var actualWrites = new java.util.concurrent.atomic.AtomicInteger();
		when(client.readStockQuantity("123", null, product.getSbCode())).thenReturn(read(17));
		when(client.readStockQuantity("123", "456", product.getSbCode())).thenReturn(read(17));
		doAnswer(call -> {
			call.<Runnable>getArgument(5).run();
			actualWrites.incrementAndGet();
			throw new MarketTransferFailure("ELEVENST_BUSINESS_500", "비지니스 Error", null, null);
		}).when(client).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("VERIFY");
		release();
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("BLOCKED");
		assertThat(actualWrites).hasValue(1);
		assertThat(tasks.findByReviewIdOrderById(review.id()).getFirst().getWrites()).isEqualTo(1);
		assertThat(tasks.findByReviewIdOrderById(review.id()).getFirst().isWriteRejected()).isTrue();
	}

	@Test
	void elevenstInventoryChangedAfterWriteCannotBeConfirmedAgainstAnotherStockItem() {
		elevenstRegistration();
		var review = elevenstQueue();
		when(client.readStockQuantity("123", null, product.getSbCode())).thenReturn(read(17));
		service.processOne(MarketType.ELEVEN_STREET);
		release();
		when(client.readStockQuantity("123", "456", product.getSbCode()))
			.thenReturn(new MarketStockRead(300, true, "different inventory", "account-A", "457"));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("UNKNOWN");
		verify(client, times(1)).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
	}

	@Test
	void elevenstReviewedQuantitySaveCreatesDedicatedTargetAndPreservesActualSourceStock() {
		elevenstRegistration();
		int sourceStock = product.getStock();
		var target = savedQuantity(450);
		assertThat(target.getMarket()).isEqualTo("ELEVEN_STREET");
		service.dispatchSavedQuantities();
		when(client.readStockQuantity("123", null, product.getSbCode())).thenReturn(read(450));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(changeTargets.findById(target.getId()).orElseThrow().getState()).isEqualTo("CONFIRMED_QUANTITY");
		assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(sourceStock);
		verify(client, never()).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
	}

	@Test
	void connectedElevenstZeroAndTruncatedFractionRemainReviewableAndDoNotChangeSourceStock() {
		elevenstRegistration();
		for (double quantity : List.of(0.0, 0.9)) {
			var review = edits.previewSingle(product.getId(), product.getRevision(),
				mapper.createObjectNode().put("salesQuantity", quantity), "admin");
			assertThat(review.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.READY);
		}
		var review = edits.previewSingle(product.getId(), product.getRevision(),
			mapper.createObjectNode().put("salesQuantity", 0.9), "admin");
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		assertThat(products.findById(product.getId()).orElseThrow().getSalesQuantity()).isZero();
		assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(product.getStock());
		assertThat(changeTargets.findByProductIdAndMarket(product.getId(), "ELEVEN_STREET")).hasSize(1);
	}

	@Test
	void elevenstZeroReadWhileStillSellingCannotCreateSuccessEvenThroughPublicFinish() {
		elevenstRegistration();
		jdbc.update("update sb_product set sales_quantity=0 where id=?", product.getId());
		var review = elevenstQueue();
		var claim = service.claim(MarketType.ELEVEN_STREET);
		service.finish(claim, "CONFIRMED_QUANTITY", "incorrect shortcut", read(0), null);
		assertThat(state(review.id())).isEqualTo("UNKNOWN");
		assertThat(service.get(review.id(), "admin").items().getFirst().observedSaleState()).isEqualTo("103");
	}

	@Test
	void elevenstProductSoldOutWithUsableInventoryDoesNotProveTheRequestedSoldOutTransition() {
		elevenstRegistration();
		jdbc.update("update sb_product set sales_quantity=0 where id=?", product.getId());
		var review = elevenstQueue();
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenReturn(new MarketStockRead(0, true, "품절/재고 사용 불일치", "account-A", "456", "104", "01"));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("VERIFY");
		assertThat(service.get(review.id(), "admin").items().getFirst().observedStockState()).isEqualTo("01");
		verify(client).writeStockQuantity(eq("123"), eq("456"), eq(product.getSbCode()), eq(0), eq("account-A"), any());
	}

	@Test
	void elevenstZeroWhileStillSellingExhaustsThreeMutationLimitWithoutFalseSuccess() {
		elevenstRegistration();
		jdbc.update("update sb_product set sales_quantity=0 where id=?", product.getId());
		var review = elevenstQueue();
		when(client.readStockQuantity(any(), any(), any())).thenReturn(read(0));
		for (int i = 0; i < 4; i++) {
			service.processOne(MarketType.ELEVEN_STREET);
			release();
		}
		assertThat(state(review.id())).isEqualTo("FAILED_MISMATCH");
		var item = service.get(review.id(), "admin").items().getFirst();
		assertThat(item.observedQuantity()).isZero();
		assertThat(item.observedSaleState()).isEqualTo("103");
		assertThat(item.observedStockState()).isEqualTo("01");
		assertThat(item.writes()).isEqualTo(3);
		verify(client, times(4)).readStockQuantity(any(), any(), any());
		verify(client, times(4)).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		assertThat(
			service.history(item.id(), "admin").stream().filter(a -> "WRITE_STARTED".equals(a.getPhase())).count())
			.isEqualTo(3);
	}

	@Test
	void elevenstDisplayStopKeepsRawQuantityAndNeedsInventoryZeroBeforeSuccess() {
		elevenstRegistration();
		jdbc.update("update sb_product set sales_quantity=0 where id=?", product.getId());
		var review = elevenstQueue();
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenReturn(new MarketStockRead(300, true, "전시중지", "account-A", "456", "105", "01"));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("VERIFY");
		assertThat(service.get(review.id(), "admin").items().getFirst().observedQuantity()).isEqualTo(300);
		release();
		when(client.readStockQuantity("123", "456", product.getSbCode()))
			.thenReturn(new MarketStockRead(0, true, "전시중지", "account-A", "456", "105", "02"));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("CONFIRMED_QUANTITY");
		assertThat(service.get(review.id(), "admin").items().getFirst().detail()).contains("전시중지(105)")
			.doesNotContain("품절(104)");
	}

	@Test
	void elevenstMatchingPositiveQuantityStillRequiresSellingAndUsableInventoryStates() {
		elevenstRegistration();
		var review = elevenstQueue();
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenReturn(new MarketStockRead(300, true, "전시중지", "account-A", "456", "105", "01"));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("VERIFY");
		release();
		when(client.readStockQuantity("123", "456", product.getSbCode()))
			.thenReturn(new MarketStockRead(300, true, "재고 품절", "account-A", "456", "103", "02"));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("VERIFY");
		verify(client, times(2)).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		release();
		when(client.readStockQuantity("123", "456", product.getSbCode())).thenReturn(read(300));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("CONFIRMED_QUANTITY");
	}

	@Test
	void elevenstLegacyQuantityOnlyObservationCannotWriteOrConfirm() {
		elevenstRegistration();
		var review = elevenstQueue();
		when(client.readStockQuantity("123", null, product.getSbCode()))
			.thenReturn(new MarketStockRead(300, true, "legacy", "account-A", "456"));
		service.processOne(MarketType.ELEVEN_STREET);
		assertThat(state(review.id())).isEqualTo("UNKNOWN");
		verify(client, never()).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
	}

	@Test
	void elevenstEngineNeverRestartsForcedEndOrProhibitionDespiteWritableFlag() {
		elevenstRegistration();
		for (String blocked : List.of("107", "108")) {
			var review = elevenstQueue();
			when(client.readStockQuantity("123", null, product.getSbCode()))
				.thenReturn(new MarketStockRead(300, true, "상태 " + blocked, "account-A", "456", blocked, "01"));
			service.processOne(MarketType.ELEVEN_STREET);
			assertThat(state(review.id())).isEqualTo("BLOCKED");
			release();
		}
		verify(client, never()).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
	}

	@Autowired
	jakarta.persistence.EntityManager entityManager;

	String batchOwns(String reviewId, String runState) {
		String id = UUID.randomUUID().toString();
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			var now = Instant.now();
			var run = new com.sbshop.agent.core.domain.product.batch.ProductSupplierBatchRun(id, id,
				"admin", "IHB", "STOCK", "{}", "{}", "[]", 1, now);
			if (!"RUNNING".equals(runState))
				run.pause(now);
			entityManager.persist(run);
			var item = new com.sbshop.agent.core.domain.product.batch.ProductSupplierBatchItem(id,
				product.getId(), product.getSbCode(), "fixture", null, now);
			entityManager.persist(item);
			entityManager.flush();
			var stage = new com.sbshop.agent.core.domain.product.batch.ProductSupplierBatchStage(id,
				item.getId(), "MARKET", MARKET.name(), "STOCK", now);
			stage.reference(reviewId);
			entityManager.persist(stage);
		});
		return id;
	}

	@Test
	void pausedBatchBlocksMarketReadAndTheSameTaskRunsAfterResume() {
		var review = queue();
		String batch = batchOwns(review.id(), "PAUSING");
		service.processOne(MARKET);
		verify(client, never()).readStockQuantity(any(), any(), any());
		assertThat(tasks.findByReviewIdOrderById(review.id()).getFirst().getReads()).isZero();
		jdbc.update("update sb_supplier_batch_run set state='RUNNING' where id=?", batch);
		observed(300);
		service.processOne(MARKET);
		assertThat(state(review.id())).isEqualTo("CONFIRMED_QUANTITY");
		assertThat(tasks.findByReviewIdOrderById(review.id()).getFirst().getWrites()).isZero();
	}

	@Test
	void pauseDuringReadStopsTheWriteAtItsLastAdmissionGuard() {
		var review = queue();
		String batch = batchOwns(review.id(), "RUNNING");
		when(client.readStockQuantity(any(), any(), any())).thenAnswer(call -> {
			jdbc.update("update sb_supplier_batch_run set state='PAUSING' where id=?", batch);
			return read(10);
		});
		var actualPut = new java.util.concurrent.atomic.AtomicBoolean();
		doAnswer(call -> {
			call.<Runnable>getArgument(5).run();
			actualPut.set(true);
			return null;
		})
			.when(client).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		service.processOne(MARKET);
		assertThat(actualPut).isFalse();
		assertThat(state(review.id())).isEqualTo("VERIFY");
		assertThat(tasks.findByReviewIdOrderById(review.id()).getFirst().getWrites()).isZero();
		assertThat(attempts.findByTaskIdOrderById(tasks.findByReviewIdOrderById(review.id()).getFirst().getId()))
			.anyMatch(a -> "BATCH_PAUSED".equals(a.getPhase()));
	}

	@Test
	void pausedEarlierTaskDoesNotHideLaterUnownedWorkFromDueQuery() {
		var review = queue();
		batchOwns(review.id(), "PAUSING");
		var original = tasks.findByReviewIdOrderById(review.id()).getFirst();
		var other = tasks.saveAndFlush(new MarketStockTask(UUID.randomUUID().toString(), 999999L,
			"SB-other", reg.getId(), 0, 0, MARKET.name(), "999", "888", "{}", "account-A", 300,
			null, Instant.now()));
		assertThat(tasks.due(MARKET.name(), Instant.now().plusSeconds(1),
			org.springframework.data.domain.PageRequest.of(0, 1)))
			.extracting(MarketStockTask::getId).containsExactly(other.getId());
		assertThat(original.getReads()).isZero();
	}

}
