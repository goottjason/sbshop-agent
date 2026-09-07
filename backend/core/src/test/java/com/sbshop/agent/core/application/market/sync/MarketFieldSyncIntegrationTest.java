package com.sbshop.agent.core.application.market.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.edit.*;
import com.sbshop.agent.core.domain.product.enums.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
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
	"spring.datasource.url=jdbc:h2:mem:fieldsync;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketFieldSyncIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketFieldSyncIntegrationTest {
	@org.springframework.test.context.DynamicPropertySource
	static void postgres(org.springframework.test.context.DynamicPropertyRegistry r) {
		String url = System.getenv("SBSHOP_FIELD_TEST_POSTGRES_URL");
		if (url == null)
			return;
		if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sbshop_field_check"))
			throw new IllegalArgumentException("Only isolated field test database allowed");
		r.add("spring.datasource.url", () -> url);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.datasource.username", () -> "postgres");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketFieldTaskRepository.class, MarketInspectionGateRepository.class})
	@Import({MarketFieldSyncService.class, ProductEditPlanner.class, ProductEditPolicy.class})
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}

	@Autowired
	MarketFieldSyncService service;
	@Autowired
	ProductEditPlanner planner;
	@Autowired
	ProductChangeTargetRepository targets;
	@Autowired
	ProductChangeHistoryRepository histories;
	@Autowired
	MarketFieldTaskRepository tasks;
	@Autowired
	MarketFieldReviewRepository reviews;
	@Autowired
	MarketFieldAttemptRepository attempts;
	@Autowired
	MarketInspectionGateRepository gates;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	ObjectMapper mapper;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactions;
	@MockitoBean
	MarketClientRouter clients;
	@MockitoBean
	com.sbshop.agent.core.application.product.MarketSalePriceResolver prices;
	MarketClient client;
	Product product;
	MarketRegistration reg;
	static final MarketType MARKET = MarketType.COUPANG;
	static final Set<String> FIELDS = Set.of("brand", "detailHtml");
	static final Map<String, String> EXPECTED = Map.of("brand", "브랜드", "detailHtml", "<p>최신 상세</p>");

	@BeforeEach
	void setup() {
		targets.deleteAll();
		histories.deleteAll();
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
		product = products.saveAndFlush(Product.create("SB-" + UUID.randomUUID(),
			new ProductCreateCommand("https://example.com/item", new BigDecimal("10000"), "상품", "original", "브랜드", "US",
				new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "html", "FOOD", true,
				1, new BigDecimal("20"), VendorType.IHB, null)));
		reg = registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId()).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"123\",\"vendorItemId\":\"456\"}").build());
		when(client.prepareProductFields(any(), eq("123"), eq("456"), eq(FIELDS))).thenReturn(prepared(false));
		when(client.readProductFields("123", "456", product.getSbCode(), FIELDS))
			.thenReturn(read(EXPECTED, MarketFieldsRead.Approval.NOT_REQUIRED));
		doAnswer(c -> {
			c.<Runnable>getArgument(4).run();
			return null;
		}).when(client).writePreparedProductFields(any(), any(), any(), any(), any());
	}

	PreparedMarketFields prepared(boolean approval) {
		return new PreparedMarketFields("account-A", "456", approval, EXPECTED, "{\"delta\":true}");
	}

	MarketFieldsRead read(Map<String, String> values, MarketFieldsRead.Approval approval) {
		return new MarketFieldsRead(values, "account-A", "456", approval, null);
	}

	MarketFieldsRead mismatch() {
		return read(Map.of("brand", "old", "detailHtml", "old"), MarketFieldsRead.Approval.NOT_REQUIRED);
	}

	MarketFieldSyncService.Review preview() {
		return service.preview(List.of(product.getId()), Set.of(MARKET), FIELDS, "admin");
	}

	String queue(boolean approval){when(client.prepareProductFields(any(),any(),any(),any())).thenReturn(prepared(approval));var r=preview();service.processOne(MARKET);service.commit(r.id(),approval,"admin");release();return r.id();}

	void release() {
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
		jdbc.update("update sb_market_field_task set next_run_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
	}

	String state(String id) {
		return service.get(id, "admin").items().getFirst().state();
	}

	MarketFieldTask task() {
		return tasks.findAll().getFirst();
	}

	void noWrite() {
		verify(client, never()).writePreparedProductFields(any(), any(), any(), any(), any());
	}

	ProductChangeTarget target() throws Exception {
		String snap = mapper.writeValueAsString(
			Map.of("connectionFingerprint", planner.fingerprint(registrations.findByProductId(product.getId())),
				"changes", List.of(Map.of("field", "brand"), Map.of("field", "detailHtml"))));
		var h = histories.saveAndFlush(new ProductChangeHistory(UUID.randomUUID().toString(), product.getId(),
			product.getRevision(), product.getRevision(), "admin", "[]", snap));
		return targets.saveAndFlush(new ProductChangeTarget(h.getId(), product.getId(), reg.getId(),
			product.getRevision(), MARKET.name(), snap));
	}

	@Test
	void preparationIsDurableAndNoNetworkInRequestTransaction() {
		var r = preview();
		assertThat(r.preparing()).isTrue();
		verify(client, never()).prepareProductFields(any(), any(), any(), any());
		assertThatThrownBy(() -> service.commit(r.id(), false, "admin"))
			.isInstanceOf(ProductEditConflictException.class);
		when(client.prepareProductFields(any(), any(), any(), any())).thenAnswer(c -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return prepared(false);
		});
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("DRAFT");
		assertThat(service.get(r.id(), "admin").items().getFirst().expectedValues()).isEqualTo(EXPECTED);
		noWrite();
	}

	@Test
	void cannotReadOrCommitAnotherActorsReview() {
		var r = preview();
		assertThatThrownBy(() -> service.get(r.id(), "other")).isInstanceOf(ProductEditConflictException.class);
		assertThatThrownBy(() -> service.commit(r.id(), true, "other"))
			.isInstanceOf(ProductEditConflictException.class);
		assertThatThrownBy(() -> service.history(r.items().getFirst().id(), "other"))
			.isInstanceOf(ProductEditConflictException.class);
	}

	@Test void approvalConsentExplicitAndCommitRetryDoesNotDuplicateTask() {
  when(client.prepareProductFields(any(),any(),any(),any())).thenReturn(prepared(true));var r=preview();service.processOne(MARKET);
  assertThatThrownBy(()->service.commit(r.id(),false,"admin")).isInstanceOf(ProductEditConflictException.class);
  assertThat(state(r.id())).isEqualTo("DRAFT");service.commit(r.id(),true,"admin");service.commit(r.id(),false,"admin");assertThat(tasks.count()).isOne();assertThat(reviews.findById(r.id()).orElseThrow().isApprovalConsent()).isTrue();
 }

	@Test
	void exactIndependentReadConfirmsOnlyFieldsWithoutPut() {
		String id = queue(false);
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("CONFIRMED_FIELDS");
		assertThat(task().getObservedValues()).contains("최신 상세");
		noWrite();
	}

	@Test
	void putReceiptAloneNeverConfirmsAndNextReadMayConfirm() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any())).thenReturn(mismatch());
		doAnswer(c -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			c.<Runnable>getArgument(4).run();
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(task().getWrites()).isOne();
			return null;
		}).when(client).writePreparedProductFields(any(), any(), any(), any(), any());
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("VERIFY");
		release();
		when(client.readProductFields(any(), any(), any(), any()))
			.thenReturn(read(EXPECTED, MarketFieldsRead.Approval.NOT_REQUIRED));
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("CONFIRMED_FIELDS");
		verify(client, times(1)).writePreparedProductFields(any(), any(), any(), any(), any());
	}

	@Test
	void pendingApprovalNeverWritesOrReportsFieldSuccess() {
		String id = queue(true);
		when(client.readProductFields(any(), any(), any(), any()))
			.thenReturn(read(EXPECTED, MarketFieldsRead.Approval.PENDING));
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("AWAITING_APPROVAL");
		assertThat(task().getNextRunAt()).isAfter(Instant.now().plusSeconds(800));
		noWrite();
		release();
		when(client.readProductFields(any(), any(), any(), any()))
			.thenReturn(read(EXPECTED, MarketFieldsRead.Approval.APPROVED));
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("CONFIRMED_FIELDS");
	}

	@Test
	void rejectedApprovalBlocksEvenMatchingValues() {
		String id = queue(true);
		when(client.readProductFields(any(), any(), any(), any()))
			.thenReturn(read(EXPECTED, MarketFieldsRead.Approval.REJECTED));
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("REJECTED");
		noWrite();
	}

	@Test
	void requiredApprovalCannotBeInferredFromNotRequired() {
		String id = queue(true);
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("VERIFY");
		noWrite();
	}

	@Test void fullExpectedKeysRequiredDuringPreparation() {when(client.prepareProductFields(any(),any(),any(),any())).thenReturn(new PreparedMarketFields("account-A","456",false,Map.of("brand","브랜드"),"{}"));var r=preview();service.processOne(MARKET);assertThat(state(r.id())).isEqualTo("BLOCKED");noWrite();}

	@Test
	void partialReadOrWrongOptionNeverConfirmsOrWrites() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any()))
			.thenReturn(read(Map.of("brand", "브랜드"), MarketFieldsRead.Approval.NOT_REQUIRED));
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("VERIFY");
		release();
		when(client.readProductFields(any(), any(), any(), any())).thenReturn(
			new MarketFieldsRead(EXPECTED, "account-A", "different", MarketFieldsRead.Approval.NOT_REQUIRED, null));
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("VERIFY");
		noWrite();
	}

	@Test
	void noSilentSuccessForStoppedListing() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any())).thenReturn(
			new MarketFieldsRead(EXPECTED, "account-A", "456", MarketFieldsRead.Approval.NOT_REQUIRED, "판매금지"));
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("BLOCKED");
		noWrite();
	}

	@Test
	void revisionChangedDuringPreparationDiscardsFrozenPayload() {
		var r = preview();
		when(client.prepareProductFields(any(), any(), any(), any())).thenAnswer(c -> {
			jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
			return prepared(false);
		});
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("STALE");
		assertThat(task().getPreparedPayload()).isNull();
		noWrite();
	}

	@Test
	void revisionChangedAfterReadStopsWriteAtDurableGuard() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any())).thenAnswer(c -> {
			jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
			return mismatch();
		});
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("STALE");
		assertThat(task().getWrites()).isZero();
	}

	@Test
	void connectionChangeAfterPreparationStopsCommit() {
		var r = preview();
		service.processOne(MARKET);
		jdbc.update("update sb_market_registration set revision=revision+1 where id=?", reg.getId());
		service.commit(r.id(), false, "admin");
		assertThat(state(r.id())).isEqualTo("STALE");
		noWrite();
	}

	@Test
	void timeoutAfterAppliedWriteReadsBeforeRetry() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any())).thenReturn(mismatch());
		doAnswer(c -> {
			c.<Runnable>getArgument(4).run();
			throw new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null);
		}).when(client).writePreparedProductFields(any(), any(), any(), any(), any());
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("VERIFY");
		release();
		when(client.readProductFields(any(), any(), any(), any()))
			.thenReturn(read(EXPECTED, MarketFieldsRead.Approval.NOT_REQUIRED));
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("CONFIRMED_FIELDS");
		verify(client, times(1)).writePreparedProductFields(any(), any(), any(), any(), any());
	}

	@Test
	void explicitWrite400IsReadOnceThenBlockedWithoutSecondPut() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any())).thenReturn(mismatch());
		doAnswer(c -> {
			c.<Runnable>getArgument(4).run();
			throw new MarketTransferFailure("HTTP_400", "rejected", null, null);
		}).when(client).writePreparedProductFields(any(), any(), any(), any(), any());
		service.processOne(MARKET);
		assertThat(task().isWriteRejected()).isTrue();
		release();
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("BLOCKED");
		assertThat(task().getWrites()).isOne();
	}

	@Test
	void threeWritesStopOnPersistentMismatch() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any())).thenReturn(mismatch());
		for (int i = 0; i < 4; i++) {
			service.processOne(MARKET);
			release();
		}
		assertThat(state(id)).isEqualTo("FAILED_MISMATCH");
		assertThat(task().getWrites()).isEqualTo(3);
	}

	@Test
	void nineConsecutiveInvalidReadsBecomeExplicitUnknown() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any())).thenThrow(new IllegalStateException("bad"));
		for (int i = 0; i < 9; i++) {
			service.processOne(MARKET);
			release();
		}
		assertThat(state(id)).isEqualTo("UNKNOWN");
		noWrite();
	}

	@Test
	void late429PreservesNewLeaseAndPreventsItsWrite() {
		String id = queue(false);
		var old = service.claim(MARKET);
		release();
		var current = service.claim(MARKET);
		Instant retry = Instant.now().plusSeconds(600);
		service.finish(old, "VERIFY", "late429", null, new MarketTransferFailure("HTTP_429", "limited", retry, null));
		assertThat(gates.findById(MARKET.name() + "_ORIGIN_READ").orElseThrow().getLeaseToken())
			.isEqualTo(current.token());
		assertThat(service.beginWrite(current, mismatch())).isFalse();
		assertThat(task().getWrites()).isZero();
		assertThat(state(id)).isEqualTo("VERIFY");
		assertThat(task().getNextRunAt()).isAfterOrEqualTo(retry);
	}

	@Test
	void abandonedWriteIntentIsReclaimedForReadNotBlindPut() {
		String id = queue(false);
		var first = service.claim(MARKET);
		assertThat(service.beginWrite(first, mismatch())).isTrue();
		release();
		service.processOne(MARKET);
		assertThat(state(id)).isEqualTo("CONFIRMED_FIELDS");
		assertThat(task().getWrites()).isOne();
		noWrite();
	}

	@Test
	void manualDraftExpiresAndReleasesActiveSlot() {
		var r = preview();
		service.processOne(MARKET);
		jdbc.update("update sb_market_field_review set expires_at=?", java.sql.Timestamp.from(Instant.EPOCH));
		release();
		service.processOne(MARKET);
		assertThat(state(r.id())).isEqualTo("EXPIRED");
		assertThat(preview().items().getFirst().state()).isEqualTo("PREPARE");
	}

	@Test
	void anotherPreviewDoesNotDuplicateActiveProductMarket() {
		preview();
		assertThat(preview().items().getFirst().state()).isEqualTo("SKIPPED");
		assertThat(tasks.active(product.getId(), MARKET.name())).isOne();
	}

	@Test
	void savedNonApprovalFieldsAutomaticallyPrepareReadAndConfirm() throws Exception {
		var target = target();
		service.dispatchSavedFields();
		service.dispatchSavedFields();
		assertThat(tasks.count()).isOne();
		assertThat(targets.findById(target.getId()).orElseThrow().getFieldTaskId()).isEqualTo(task().getId());
		service.processOne(MARKET);
		assertThat(task().getState()).isEqualTo("CHECK");
		release();
		service.processOne(MARKET);
		assertThat(targets.findById(target.getId()).orElseThrow().getState()).isEqualTo("CONFIRMED_FIELDS");
	}

	@Test
	void savedApprovalFieldsAwaitActorConsentWithoutAnyWrite() throws Exception {
		var target = target();
		when(client.prepareProductFields(any(), any(), any(), any())).thenReturn(prepared(true));
		service.dispatchSavedFields();
		service.processOne(MARKET);
		assertThat(targets.findById(target.getId()).orElseThrow().getState()).isEqualTo("AWAITING_REVIEW");
		assertThat(task().getState()).isEqualTo("DRAFT");
		noWrite();
		service.commit(task().getReviewId(), true, "admin");
		release();
		when(client.readProductFields(any(), any(), any(), any()))
			.thenReturn(read(EXPECTED, MarketFieldsRead.Approval.APPROVED));
		service.processOne(MARKET);
		assertThat(targets.findById(target.getId()).orElseThrow().getState()).isEqualTo("CONFIRMED_FIELDS");
	}

	@Test
	void changedSavedRevisionIsNotDispatched() throws Exception {
		var target = target();
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		service.dispatchSavedFields();
		assertThat(tasks.count()).isZero();
		assertThat(targets.findById(target.getId()).orElseThrow().getState()).isEqualTo("ACTION_REQUIRED");
	}

	@Test
	void targetQueueAndAttachmentRollbackTogether() throws Exception {
		var target = target();
		jdbc.execute(
			"alter table sb_product_change_target add constraint field_dispatch_fixture check (field_task_id is null)");
		try {
			assertThatThrownBy(service::dispatchSavedFields).isInstanceOf(RuntimeException.class);
			assertThat(tasks.count()).isZero();
			assertThat(reviews.count()).isZero();
			assertThat(targets.findById(target.getId()).orElseThrow().getState()).isEqualTo("PENDING_DISPATCH");
		} finally {
			jdbc.execute("alter table sb_product_change_target drop constraint field_dispatch_fixture");
		}
	}

	@Test
	void disjointDispatchDoesNotTakePriceQuantityOrMixedSnapshots() throws Exception {
		for (var fs : List.of(List.of("salePrice"), List.of("salesQuantity"), List.of("brand", "salesQuantity"),
			List.of("brand", "costPrice"))) {
			var t = new ProductChangeTarget(1L, 1L, 1L, 0, MARKET.name(),
				mapper.writeValueAsString(Map.of("changes", fs.stream().map(f -> Map.of("field", f)).toList())));
			assertThat(MarketFieldSyncService.handlesSavedFieldsTarget(t, mapper)).as(fs.toString()).isFalse();
		}
	}

	@Test
	void detachedConnectionIsSkipped() {
		jdbc.update("update sb_market_registration set connection_state='DETACHED_DELETED' where id=?", reg.getId());
		assertThat(preview().items().getFirst().state()).isEqualTo("SKIPPED");
		noWrite();
	}

	@Test
	void multipleProviderPutsRecordSeparateIntentsAndDoNotExceedThreeRequests() {
		String id = queue(false);
		when(client.readProductFields(any(), any(), any(), any())).thenReturn(mismatch());
		java.util.concurrent.atomic.AtomicInteger physicalPuts = new java.util.concurrent.atomic.AtomicInteger();
		doAnswer(c -> {
			c.<Runnable>getArgument(4).run();
			physicalPuts.incrementAndGet();
			c.<Runnable>getArgument(4).run();
			physicalPuts.incrementAndGet();
			return null;
		}).when(client).writePreparedProductFields(any(), any(), any(), any(), any());
		service.processOne(MARKET);
		assertThat(task().getWrites()).isEqualTo(2);
		assertThat(state(id)).isEqualTo("VERIFY");
		release();
		service.processOne(MARKET);
		assertThat(physicalPuts.get()).isEqualTo(3);
		assertThat(task().getWrites()).isEqualTo(3);
		assertThat(state(id)).isEqualTo("FAILED_MISMATCH");
	}

	@Test
	void rowLockSerializesConcurrentEnqueueWithoutDuplicateActiveWork() throws Exception {
		var held = new CountDownLatch(1);
		var unlock = new CountDownLatch(1);
		var entered = new CountDownLatch(2);
		try (var pool = Executors.newFixedThreadPool(3)) {
			var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
				products.findForEdit(product.getId()).orElseThrow();
				held.countDown();
				try {
					assertThat(unlock.await(5, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}));
			assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
			var first = pool.submit(() -> {
				entered.countDown();
				return preview();
			});
			var second = pool.submit(() -> {
				entered.countDown();
				return preview();
			});
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> first.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			} finally {
				unlock.countDown();
			}
			holder.get(5, TimeUnit.SECONDS);
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
		}
		assertThat(tasks.findAll()).extracting(MarketFieldTask::getState).containsExactlyInAnyOrder("PREPARE",
			"SKIPPED");
	}

	@Test
	void workerRefreshesDraftAfterWaitingForConcurrentReviewCommit() throws Exception {
		var review = preview();
		service.processOne(MARKET);
		release();
		var held = new CountDownLatch(1);
		var unlock = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var committer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
				var r = reviews.lock(review.id()).orElseThrow();
				var t = tasks.findByReviewIdOrderById(review.id()).getFirst();
				t.queue(Instant.now());
				r.commit(false, Instant.now());
				held.countDown();
				try {
					assertThat(unlock.await(5, TimeUnit.SECONDS)).isTrue();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}));
			assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
			var worker = pool.submit(() -> service.claim(MARKET));
			try {
				assertThatThrownBy(() -> worker.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			} finally {
				unlock.countDown();
			}
			committer.get(5, TimeUnit.SECONDS);
			var claim = worker.get(5, TimeUnit.SECONDS);
			assertThat(claim).isNotNull();
			assertThat(claim.phase()).isEqualTo("CHECK");
			assertThat(task().getState()).isEqualTo("CHECK");
		}
	}

	@Test
    void invalidPreparedInputStopsWithoutRepeatedProviderCalls() {
        when(client.prepareProductFields(any(),any(),any(),any())).thenThrow(new IllegalArgumentException("상품명 길이를 확인하세요."));
        var review=preview();service.processOne(MARKET);
        assertThat(state(review.id())).isEqualTo("BLOCKED");
        assertThat(service.get(review.id(),"admin").items().getFirst().detail()).contains("상품명 길이");
        service.processOne(MARKET);
        verify(client,times(1)).prepareProductFields(any(),any(),any(),any());noWrite();
    }

}
