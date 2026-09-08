package com.sbshop.agent.core.application.product.batch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.market.sync.*;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.application.product.batch.ProductSupplierBatchService.*;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.application.product.source.*;
import com.sbshop.agent.core.application.product.source.ProductSourceData.Observed;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.batch.*;
import com.sbshop.agent.core.domain.product.content.*;
import com.sbshop.agent.core.domain.product.dto.*;
import com.sbshop.agent.core.domain.product.edit.*;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.core.domain.product.service.SalePriceRounding;
import com.sbshop.agent.core.domain.product.source.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:supplierbatch;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductSupplierBatchIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductSupplierBatchIntegrationTest {
	@org.springframework.test.context.DynamicPropertySource
	static void isolatedPostgres(org.springframework.test.context.DynamicPropertyRegistry registry) {
		String url = System.getenv("SBSHOP_BATCH_TEST_POSTGRES_URL");
		if (url == null)
			return;
		if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sbshop_batch_flow_check"))
			throw new IllegalArgumentException("Only the local isolated batch test database is allowed");
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketPriceTaskRepository.class})
	@Import({ProductSupplierBatchService.class, ProductSupplierBatchRunner.class, ProductSupplierBatchSource.class,
		ProductSourceService.class, ProductSourceWorker.class, ProductEditService.class, ProductEditPlanner.class,
		ProductEditPolicy.class})
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}

	@Autowired
	ProductSupplierBatchService service;
	@Autowired
	ProductSupplierBatchRunner runner;
	@Autowired
	ProductSourceService sourceService;
	@Autowired
	ProductSourceWorker sourceWorker;
	@MockitoSpyBean
	ProductSupplierBatchSource batchSource;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	ProductSupplierBatchRunRepository runs;
	@Autowired
	ProductSupplierBatchItemRepository items;
	@Autowired
	ProductSupplierBatchStageRepository stages;
	@Autowired
	ProductSourceSnapshotRepository snapshots;
	@Autowired
	ProductSourceCollectionRepository collections;
	@Autowired
	ProductSourceReviewRepository sourceReviews;
	@Autowired
	ProductContentLaneRepository lanes;
	@Autowired
	ProductChangeHistoryRepository histories;
	@Autowired
	ProductChangeTargetRepository targets;
	@Autowired
	MarketPriceTaskRepository priceTasks;
	@Autowired
	MarketStockTaskRepository stockTasks;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactions;
	@MockitoBean
	ProductSourceObservationSource source;
	@MockitoBean
	VendorPricePolicyService policies;
	@MockitoBean
	MarketSalePriceResolver priceResolver;
	@MockitoBean
	MarketClientRouter clients;
	@MockitoBean
	MarketPriceSyncService priceQueue;
	@MockitoBean
	MarketStockSyncService stockQueue;
	TransactionTemplate tx;

	static BigDecimal n(String v) {
		return new BigDecimal(v);
	}

	@BeforeEach
	void setup() {
		tx = new TransactionTemplate(transactions);
		reset(source, policies, priceResolver, clients, priceQueue, stockQueue);
		for (String table : List.of("sb_supplier_batch_retry", "sb_supplier_batch_attempt", "sb_supplier_batch_stage",
			"sb_supplier_batch_item", "sb_supplier_batch_run", "sb_product_change_target", "sb_product_change_history",
			"sb_product_source_review", "sb_product_source_snapshot", "sb_product_source_collection",
			"sb_market_price_task", "sb_market_stock_task"))
			jdbc.update("delete from " + table);
		lanes.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		for (String lane : List.of("PRICE_STOCK", "SOURCE_IHB", "SOURCE_VTB", "SOURCE_FTN", "SOURCE_COK", "SOURCE_OCD"))
			lanes.saveAndFlush(new ProductContentLane(lane));
		when(policies.find(VendorType.IHB)).thenReturn(Optional
			.of(VendorPricePolicy.builder().vendor(VendorType.IHB).shipCurrency("KRW").shipBaseAmount(BigDecimal.ZERO)
				.marginRate(n("25")).couponRate(BigDecimal.ZERO).minMarginPrice(n("1000")).build()));
		doReturn(new Observed(n("12000"), BigDecimal.ONE, "KRW", StockStatus.OUT_OF_STOCK, null, List.of()))
			.when(source).fetch(any(), anyString());
		when(priceResolver.explainForProduct(any(), any(), any())).thenReturn(new MarketSalePriceResolver.Explanation(
			MarketSalePriceResolver.Basis.CALCULATED, SalePriceRounding.fromPrice(n("18000"), n("15000"))));
		var client = mock(MarketClient.class);
		when(client.inspectionAccountReference()).thenReturn("fixed-account");
		when(clients.hasClient(any())).thenReturn(true);
		when(clients.getClient(any())).thenReturn(client);
	}

	Product product() {
		return tx.execute(s -> products.saveAndFlush(newProduct()));
	}

	Product newProduct() {
		var p = Product.create("SB-" + UUID.randomUUID(),
			new ProductCreateCommand("https://kr.iherb.com/pr/example/12345", n("10000"), "테스트 상품", "Original", "브랜드",
				"US", n("0.3"), n("25"), MeasureUnit.G, List.of(), List.of(), "상세", "FOOD", true, 3, n("20"),
				VendorType.IHB, null));
		p.update(ProductUpdateCommand.builder().salePrice(n("10000")).stock(77).build());
		return p;
	}

	CreateRequest request(Mode mode) {
		return new CreateRequest(UUID.randomUUID().toString(), VendorType.IHB, mode, n("30"), n("5"), n("1000"),
			Set.of(MarketType.COUPANG));
	}

	View create(Mode mode) {
		return service.create(request(mode), "admin");
	}

	ProductSupplierBatchService.Item item(String run) {
		return service.items(run, 0, 50, "", "ALL").getContent().getFirst();
	}

	ProductSupplierBatchStage stage(String run, String name) {
		return stages.findByItemIdOrderById(item(run).id()).stream().filter(s -> s.getStage().equals(name)).findFirst()
			.orElseThrow();
	}

	void open() {
		jdbc.update("update sb_supplier_batch_stage set next_run_at=? where state in ('WAITING','RUNNING')",
			java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
		jdbc.update("update sb_product_content_lane set next_allowed_at=?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
	}

	void step(String id) {
		open();
		runner.process(id);
	}

	void untilComplete(String id) {
		for (int i = 0; i < 25 && !service.get(id).state().equals("COMPLETED"); i++) {
			step(id);
			sourceWorker.tick();
		}
	}

	void failStage(String id, String name, String state, boolean retryable) {
		tx.executeWithoutResult(t -> {
			var s = stages.findById(stage(id, name).getId()).orElseThrow();
			s.outcome(state, "의도한 회귀 표본", retryable, Instant.now());
		});
	}

	@Test
	void freezesAll2114ProductsAndCountsPagesWithoutFiftyProductLimit() {
		tx.executeWithoutResult(t -> {
			for (int i = 0; i < 2114; i++)
				products.save(newProduct());
		});
		var request = request(Mode.STOCK);
		var run = service.create(request, "admin");
		product();
		assertThat(run.total()).isEqualTo(2114);
		assertThat(run.pending()).isEqualTo(2114);
		assertThat(service.create(request, "admin").id()).isEqualTo(run.id());
		var first = service.items(run.id(), 0, 100, "", "ALL");
		var last = service.items(run.id(), 21, 100, "", "ALL");
		assertThat(first.getTotalElements()).isEqualTo(2114);
		assertThat(last.getNumberOfElements()).isEqualTo(14);
		assertThat(first.getContent()).allSatisfy(i -> assertThat(i.stages()).hasSize(2));
		assertThat(service.options().vendors().stream().filter(v -> v.vendor().equals("IHB")).findFirst().orElseThrow()
			.productCount()).isEqualTo(2115);
		assertThatThrownBy(() -> service.create(request(Mode.STOCK), "admin"))
			.isInstanceOf(ProductEditConflictException.class);
		verifyNoInteractions(source, priceQueue, stockQueue);
	}

	@Test
	void sourceStockSuccessCommitsOncePreservesConfiguredQuantityAndCompletesWithUnregisteredMarketsSkipped() {
		Product p = product();
		var run = create(Mode.STOCK);
		untilComplete(run.id());
		assertThat(service.get(run.id())).satisfies(r -> {
			assertThat(r.state()).isEqualTo("COMPLETED");
			assertThat(r.succeeded()).isEqualTo(1);
			assertThat(r.processed()).isEqualTo(1);
		});
		var current = products.findById(p.getId()).orElseThrow();
		assertThat(current.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
		assertThat(current.getStock()).isEqualTo(77);
		assertThat(current.getRevision()).isEqualTo(p.getRevision() + 1);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(snapshots.findAll().getFirst().getStockAppliedAt()).isNotNull();
		step(run.id());
		assertThat(histories.count()).isEqualTo(1);
		verify(source, times(1)).fetch(any(), anyString());
		verifyNoInteractions(priceQueue, stockQueue);
	}

	@Test
	void priceModeStoresApprovedPolicyAndPriceWhileLeavingStockUnchanged() {
		Product p = product();
		var run = create(Mode.PRICE);
		untilComplete(run.id());
		var current = products.findById(p.getId()).orElseThrow();
		assertThat(service.get(run.id()).succeeded()).isEqualTo(1);
		assertThat(current.getStockStatus()).isEqualTo(p.getStockStatus());
		assertThat(current.getPriceInfo().getMarginRate()).isEqualByComparingTo("30");
		assertThat(current.getPriceInfo().getCouponRate()).isEqualByComparingTo("5");
		assertThat(service.detail(run.id(), item(run.id()).id()).priceCalculation()).isNotNull();
	}

	@Test
	void bothCompleteObservationsUseOneDatabaseCommit() {
		product();
		var run = create(Mode.PRICE_STOCK);
		untilComplete(run.id());
		assertThat(service.get(run.id()).succeeded()).isEqualTo(1);
		assertThat(histories.count()).isEqualTo(1);
		var snap = snapshots.findAll().getFirst();
		assertThat(snap.getPriceAppliedAt()).isNotNull();
		assertThat(snap.getStockAppliedAt()).isNotNull();
	}

	@Test
	void crawlFailureIsProcessedAndRetryOnlyRecrawlsFailedStage() {
		product();
		when(source.fetch(any(), anyString())).thenThrow(new IllegalStateException("upstream unavailable"));
		var run = create(Mode.STOCK);
		untilComplete(run.id());
		assertThat(service.get(run.id())).satisfies(r -> {
			assertThat(r.state()).isEqualTo("COMPLETED");
			assertThat(r.failed()).isEqualTo(1);
			assertThat(r.processed()).isEqualTo(1);
		});
		assertThat(stage(run.id(), "DB").getState()).isEqualTo("SKIPPED");
		assertThat(histories.count()).isZero();
		String oldRef = stage(run.id(), "CRAWL").getReferenceId();
		doReturn(new Observed(n("12000"), BigDecimal.ONE, "KRW", StockStatus.OUT_OF_STOCK, null, List.of()))
			.when(source).fetch(any(), anyString());
		var retry = new RetryRequest(UUID.randomUUID().toString(), null, Step.CRAWL, null, null);
		assertThat(service.retry(run.id(), retry, "admin").state()).isEqualTo("RUNNING");
		service.retry(run.id(), retry, "admin");
		untilComplete(run.id());
		assertThat(service.get(run.id()).succeeded()).isEqualTo(1);
		assertThat(collections.count()).isEqualTo(2);
		assertThat(stage(run.id(), "CRAWL").getReferenceId()).isNotEqualTo(oldRef);
		assertThat(histories.count()).isEqualTo(1);
		verify(source, times(2)).fetch(any(), anyString());
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
	void combinedModePriceOnlyObservationFailsWholeProductAndRetryCollectsBoth(boolean unsupportedMarker) {
		assertPartialObservationNeverApplies(
			new Observed(n("12000"), BigDecimal.ONE, "KRW", null, null, List.of()), Field.PRICE,
			unsupportedMarker);
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
	void combinedModeStockOnlyObservationFailsWholeProductAndRetryCollectsBoth(boolean unsupportedMarker) {
		assertPartialObservationNeverApplies(
			new Observed(null, null, "KRW", StockStatus.OUT_OF_STOCK, 0, List.of()), Field.STOCK,
			unsupportedMarker);
	}

	private void assertPartialObservationNeverApplies(Observed partial, Field available,
		boolean unsupportedMarker) {
		var original = product();
		var originalValues = ProductSourceData.Values.from(original);
		doReturn(partial).when(source).fetch(any(), anyString());
		var run = create(Mode.PRICE_STOCK);
		step(run.id());
		sourceWorker.tick();
		var partialSnapshot = snapshots.findAll().getFirst();
		assertThat(partialSnapshot.getState()).isEqualTo(ProductSourceSnapshot.State.PARTIAL);
		if (unsupportedMarker)
			tx.executeWithoutResult(t -> ReflectionTestUtils.setField(
				snapshots.findById(partialSnapshot.getId()).orElseThrow(), "state",
				ProductSourceSnapshot.State.UNSUPPORTED));
		step(run.id());
		assertThat(service.get(run.id())).satisfies(result -> {
			assertThat(result.state()).isEqualTo("COMPLETED");
			assertThat(result.processed()).isEqualTo(1);
			assertThat(result.failed()).isEqualTo(1);
			assertThat(result.succeeded()).isZero();
		});
		assertThat(stage(run.id(), "CRAWL")).satisfies(crawl -> {
			assertThat(crawl.getState()).isEqualTo("FAILED");
			assertThat(crawl.isRetryable()).isTrue();
			assertThat(crawl.getDetail()).contains(available == Field.PRICE ? "가격 확인 완료" : "재고 확인 완료",
				"어느 항목도 적용하지 않았습니다", "두 항목을 다시 확인");
		});
		assertThat(stage(run.id(), "DB").getState()).isEqualTo("SKIPPED");
		assertThat(stages.findByItemIdOrderById(item(run.id()).id()).stream()
			.filter(s -> s.getStage().equals("MARKET"))).allSatisfy(market -> {
				assertThat(market.getState()).isEqualTo("SKIPPED");
				assertThat(market.getReferenceId()).isNull();
				assertThat(market.getAttempts()).isZero();
			});
		var unchanged = products.findById(original.getId()).orElseThrow();
		assertThat(unchanged.getRevision()).isEqualTo(original.getRevision());
		assertThat(ProductSourceData.Values.from(unchanged)).usingRecursiveComparison()
			.withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(originalValues);
		assertThat(unchanged.getPriceInfo().getSalePrice())
			.isEqualByComparingTo(original.getPriceInfo().getSalePrice());
		assertThat(unchanged.getPriceInfo().getMarginRate())
			.isEqualByComparingTo(original.getPriceInfo().getMarginRate());
		assertThat(unchanged.getPriceInfo().getCouponRate()).isEqualTo(original.getPriceInfo().getCouponRate());
		assertThat(unchanged.getPriceInfo().getMinMarginPrice()).isEqualTo(original.getPriceInfo().getMinMarginPrice());
		assertThat(histories.count()).isZero();
		assertThat(targets.count()).isZero();
		assertThat(sourceReviews.count()).isZero();
		verifyNoInteractions(priceQueue, stockQueue);

		doReturn(new Observed(n("15000"), BigDecimal.ONE, "KRW", StockStatus.IN_STOCK, 8, List.of()))
			.when(source).fetch(any(), anyString());
		service.retry(run.id(), new RetryRequest(UUID.randomUUID().toString(), null, Step.CRAWL, null, null), "admin");
		untilComplete(run.id());
		assertThat(service.get(run.id()).succeeded()).isEqualTo(1);
		assertThat(collections.count()).isEqualTo(2);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(stage(run.id(), "DB").getState()).isEqualTo("SUCCEEDED");
		var fresh = snapshots.findById(item(run.id()).sourceSnapshotId()).orElseThrow();
		assertThat(fresh.getId()).isNotEqualTo(partialSnapshot.getId());
		assertThat(fresh.getPriceAppliedAt()).isNotNull();
		assertThat(fresh.getStockAppliedAt()).isNotNull();
		var applied = products.findById(original.getId()).orElseThrow();
		assertThat(applied.getRevision()).isEqualTo(original.getRevision() + 1);
		assertThat(applied.getStock()).isEqualTo(8);
		assertThat(applied.getPriceInfo().getCostPrice()).isEqualByComparingTo("15000");
		assertThat(applied.getPriceInfo().getMarginRate()).isEqualByComparingTo("30");
		var preserved = snapshots.findById(partialSnapshot.getId()).orElseThrow();
		assertThat(preserved.getPriceAppliedAt()).isNull();
		assertThat(preserved.getStockAppliedAt()).isNull();
		verify(source, times(2)).fetch(any(), anyString());
	}

	@Test
	void changedVendorBlocksBeforeForeignSourceOrPolicyCanRun() {
		var p = product();
		var run = create(Mode.STOCK);
		tx.executeWithoutResult(t -> products.findById(p.getId()).orElseThrow()
			.update(ProductUpdateCommand.builder().vendor(VendorType.VTB).build()));
		step(run.id());
		assertThat(service.get(run.id()).blocked()).isEqualTo(1);
		assertThat(stage(run.id(), "CRAWL").getDetail()).contains("소싱처");
		verifyNoInteractions(source);
		assertThat(collections.count()).isZero();
	}

	@Test
	void pausedQueuedSourceDoesNotStarveOrdinaryCollectionAndUnrecordedReferenceStillHasOwner() {
		Product p = product();
		var run = create(Mode.STOCK);
		step(run.id());
		String batchCollection = stage(run.id(), "CRAWL").getReferenceId();
		tx.executeWithoutResult(t -> stages.findById(stage(run.id(), "CRAWL").getId()).orElseThrow().reference(null));
		assertThat(service.pause(run.id(), "admin").state()).isEqualTo("PAUSED");
		var ordinary = sourceService.collect(
			new ProductSourceService.CollectionRequest(UUID.randomUUID().toString(), List.of(p.getId())), "admin");
		open();
		sourceWorker.tick();
		assertThat(sourceService.collection(batchCollection, "admin").items().getFirst().state())
			.isEqualTo(ProductSourceSnapshot.State.QUEUED);
		assertThat(sourceService.collection(ordinary.id(), "admin").items().getFirst().state())
			.isEqualTo(ProductSourceSnapshot.State.READY);
		service.resume(run.id(), "admin");
		step(run.id());
		assertThat(stage(run.id(), "CRAWL").getReferenceId()).isEqualTo(batchCollection);
		assertThat(collections.count()).isEqualTo(2);
	}

	@Test
	void pauseDuringAdmittedFetchKeepsObservationButStopsNextProduct() {
		product();
		product();
		var run = create(Mode.STOCK);
		step(run.id());
		when(source.fetch(any(), anyString())).thenAnswer(inv -> {
			assertThat(service.pause(run.id(), "admin").state()).isEqualTo("PAUSING");
			return new Observed(n("12000"), BigDecimal.ONE, "KRW", StockStatus.OUT_OF_STOCK, null, List.of());
		});
		open();
		sourceWorker.tick();
		step(run.id());
		assertThat(service.get(run.id()).state()).isEqualTo("PAUSED");
		assertThat(snapshots.findAll().getFirst().getCollectedAt()).isNotNull();
		assertThat(collections.count()).isEqualTo(1);
		assertThat(histories.count()).isZero();
		service.resume(run.id(), "admin");
		doReturn(new Observed(n("12000"), BigDecimal.ONE, "KRW", StockStatus.OUT_OF_STOCK, null, List.of()))
			.when(source).fetch(any(), anyString());
		untilComplete(run.id());
		assertThat(service.get(run.id()).succeeded()).isEqualTo(2);
	}

	@Test
	void retryOptionsSeparatesRetryableBlockedFromNonretryableFailedAndPausedRetryStaysPaused() {
		product();
		var run = create(Mode.STOCK);
		failStage(run.id(), "CRAWL", "BLOCKED", true);
		failStage(run.id(), "DB", "FAILED", false);
		var options = service.retryOptions(run.id());
		assertThat(options.retryableStageCounts().get("CRAWL")).isEqualTo(1);
		assertThat(options.retryableProducts()).isEqualTo(1);
		assertThat(options.blockedStageCount()).isEqualTo(1);
		service.pause(run.id(), "admin");
		var retry = new RetryRequest(UUID.randomUUID().toString(), null, Step.CRAWL, null, null);
		assertThat(service.retry(run.id(), retry, "admin").state()).isEqualTo("PAUSED");
		assertThat(stage(run.id(), "CRAWL").getState()).isEqualTo("WAITING");
		assertThat(stage(run.id(), "DB").getState()).isEqualTo("FAILED");
	}

	@Test
	void existingLiveCollectionCannotLosePauseOwnershipThroughRetry() {
		product();
		var run = create(Mode.STOCK);
		step(run.id());
		var s = stage(run.id(), "CRAWL");
		String ref = s.getReferenceId(), operation = s.getOperationId();
		failStage(run.id(), "CRAWL", "FAILED", true);
		service.retry(run.id(), new RetryRequest(UUID.randomUUID().toString(), null, Step.CRAWL, null, null), "admin");
		assertThat(stage(run.id(), "CRAWL").getReferenceId()).isEqualTo(ref);
		assertThat(stage(run.id(), "CRAWL").getOperationId()).isEqualTo(operation);
	}

	@Test
	void pausedDatabaseDraftExpiresButReusesValidSnapshotAndFrozenPolicy() {
		product();
		var run = create(Mode.PRICE);
		step(run.id());
		sourceWorker.tick();
		step(run.id());
		step(run.id());
		var db = stage(run.id(), "DB");
		String oldReview = db.getReferenceId();
		assertThat(oldReview).isNotNull();
		service.pause(run.id(), "admin");
		tx.executeWithoutResult(t -> ReflectionTestUtils.setField(sourceReviews.findById(oldReview).orElseThrow(),
			"expiresAt", Instant.now().minusSeconds(1)));
		service.resume(run.id(), "admin");
		step(run.id());
		assertThat(stage(run.id(), "DB").getReferenceId()).isNotEqualTo(oldReview);
		untilComplete(run.id());
		assertThat(service.get(run.id()).succeeded()).isEqualTo(1);
		assertThat(collections.count()).isEqualTo(1);
		assertThat(histories.count()).isEqualTo(1);
		verify(source, times(1)).fetch(any(), anyString());
	}

	@Test
	void databaseFailureRetainsSuccessfulCrawlAndSameReviewOnRetry() {
		product();
		var run = create(Mode.STOCK);
		step(run.id());
		sourceWorker.tick();
		step(run.id());
		step(run.id());
		String review = stage(run.id(), "DB").getReferenceId();
		doThrow(new IllegalStateException("temporary database fault")).doCallRealMethod().when(batchSource)
			.commit(review, "admin");
		step(run.id());
		assertThat(service.get(run.id()).failed()).isEqualTo(1);
		assertThat(stage(run.id(), "CRAWL").getState()).isEqualTo("SUCCEEDED");
		assertThat(histories.count()).isZero();
		service.retry(run.id(), new RetryRequest(UUID.randomUUID().toString(), null, Step.DB, null, null), "admin");
		untilComplete(run.id());
		assertThat(service.get(run.id()).succeeded()).isEqualTo(1);
		assertThat(stage(run.id(), "DB").getReferenceId()).isEqualTo(review);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(collections.count()).isEqualTo(1);
		verify(source, times(1)).fetch(any(), anyString());
	}

	@Test
	void expiredSourceEvidenceDoesNotApplyOrChangeSuccessfulCollectionTime() {
		var p = product();
		var run = create(Mode.STOCK);
		step(run.id());
		sourceWorker.tick();
		step(run.id());
		var snapshot = snapshots.findAll().getFirst();
		Instant collected = snapshot.getCollectedAt();
		tx.executeWithoutResult(t -> ReflectionTestUtils.setField(snapshots.findById(snapshot.getId()).orElseThrow(),
			"expiresAt", Instant.now().minusSeconds(1)));
		step(run.id());
		assertThat(service.get(run.id()).blocked()).isEqualTo(1);
		assertThat(stage(run.id(), "DB").getDetail()).contains("24시간");
		assertThat(histories.count()).isZero();
		assertThat(products.findById(p.getId()).orElseThrow().getRevision()).isEqualTo(p.getRevision());
		assertThat(snapshots.findById(snapshot.getId()).orElseThrow().getCollectedAt()).isEqualTo(collected);
	}

	@Test
	void retryExpiredEvidenceRecrawlsOnlyThatUnsavedProductAndPreservesOldProof() {
		product();
		var run = create(Mode.STOCK);
		step(run.id());
		sourceWorker.tick();
		step(run.id());
		var old = snapshots.findAll().getFirst();
		tx.executeWithoutResult(t -> ReflectionTestUtils.setField(snapshots.findById(old.getId()).orElseThrow(),
			"expiresAt", Instant.now().minusSeconds(1)));
		step(run.id());
		assertThat(stage(run.id(), "DB").isRetryable()).isTrue();
		service.retry(run.id(), new RetryRequest(UUID.randomUUID().toString(), null, Step.DB, null, null), "admin");
		untilComplete(run.id());
		assertThat(service.get(run.id()).succeeded()).isEqualTo(1);
		assertThat(collections.count()).isEqualTo(2);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(snapshots.findById(old.getId()).orElseThrow().getStockAppliedAt()).isNull();
		assertThat(snapshots.findById(old.getId()).orElseThrow().getCollectedAt()).isEqualTo(old.getCollectedAt());
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"revision", "sourceUrl", "vendor"})
	void expiredRetryCannotAcceptManualChangesSinceCapture(String changed) {
		var p = product();
		var run = create(Mode.STOCK);
		step(run.id());
		sourceWorker.tick();
		step(run.id());
		var snapshot = snapshots.findAll().getFirst();
		tx.executeWithoutResult(t -> ReflectionTestUtils.setField(snapshots.findById(snapshot.getId()).orElseThrow(),
			"expiresAt", Instant.now().minusSeconds(1)));
		step(run.id());
		tx.executeWithoutResult(t -> {
			var current = products.findById(p.getId()).orElseThrow();
			switch (changed) {
				case "vendor" -> current.update(ProductUpdateCommand.builder().vendor(VendorType.VTB).build());
				case "sourceUrl" -> current
					.update(ProductUpdateCommand.builder().sourceUrl("https://kr.iherb.com/pr/changed/67890").build());
				default -> ReflectionTestUtils.setField(current, "revision", current.getRevision() + 1);
			}
		});
		assertThatThrownBy(() -> service.retry(run.id(),
			new RetryRequest(UUID.randomUUID().toString(), null, Step.DB, null, null), "admin"))
			.isInstanceOf(ProductEditConflictException.class).hasMessageContaining("변경");
		assertThat(collections.count()).isEqualTo(1);
		assertThat(histories.count()).isZero();
	}

	@Test
	void databaseResponseLossAndLaterExpiryRestoresCommittedHistoryWithoutRecrawl() {
		product();
		var run = create(Mode.STOCK);
		step(run.id());
		sourceWorker.tick();
		step(run.id());
		step(run.id());
		String review = stage(run.id(), "DB").getReferenceId();
		doAnswer(call -> {
			call.callRealMethod();
			throw new IllegalStateException("response lost after database commit");
		}).doCallRealMethod().when(batchSource).commit(review, "admin");
		step(run.id());
		assertThat(histories.count()).isEqualTo(1);
		var snapshot = snapshots.findAll().getFirst();
		Instant applied = snapshot.getStockAppliedAt();
		tx.executeWithoutResult(t -> ReflectionTestUtils.setField(snapshots.findById(snapshot.getId()).orElseThrow(),
			"expiresAt", Instant.now().minusSeconds(1)));
		service.retry(run.id(), new RetryRequest(UUID.randomUUID().toString(), null, Step.DB, null, null), "admin");
		untilComplete(run.id());
		assertThat(service.get(run.id()).succeeded()).isEqualTo(1);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(collections.count()).isEqualTo(1);
		assertThat(snapshots.findById(snapshot.getId()).orElseThrow().getStockAppliedAt()).isEqualTo(applied);
	}

}
