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
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.market.inspection.MarketInspectionGateRepository;
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
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** Real source/edit/batch/price/quantity transactions; only external observations and writes are simulated. */
@DataJpaTest(showSql = false, properties = {
	"spring.datasource.url=jdbc:h2:mem:supplierbatchmarket;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
	"spring.datasource.password=", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductSupplierBatchMarketIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductSupplierBatchMarketIntegrationTest {
	@SpringBootApplication
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketPriceTaskRepository.class, MarketInspectionGateRepository.class})
	@Import({ProductSupplierBatchService.class, ProductSupplierBatchRunner.class, ProductSupplierBatchSource.class,
		ProductSourceService.class, ProductSourceWorker.class, ProductEditService.class, ProductEditPlanner.class,
		ProductEditPolicy.class, MarketPriceSyncService.class, MarketStockSyncService.class})
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}

	@Autowired
	ProductSupplierBatchService batches;
	@Autowired
	ProductSupplierBatchRunner runner;
	@Autowired
	ProductSourceWorker sourceWorker;
	@Autowired
	ProductEditService edits;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	ProductSupplierBatchStageRepository stages;
	@Autowired
	ProductSourceSnapshotRepository snapshots;
	@Autowired
	ProductSourceCollectionRepository collections;
	@Autowired
	ProductContentLaneRepository lanes;
	@Autowired
	ProductChangeHistoryRepository histories;
	@Autowired
	ProductChangeTargetRepository targets;
	@Autowired
	MarketPriceTaskRepository priceTasks;
	@Autowired
	MarketPriceReviewRepository priceReviews;
	@Autowired
	MarketStockTaskRepository stockTasks;
	@Autowired
	MarketStockReviewRepository stockReviews;
	@Autowired
	MarketStockSyncService stocks;
	@MockitoSpyBean
	MarketPriceSyncService prices;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ObjectMapper mapper;
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
	TransactionTemplate tx;
	MarketClient client;
	Product product;
	AtomicReference<BigDecimal> externalPrice;
	AtomicInteger externalQuantity;
	AtomicBoolean acceptPrice;
	static final MarketType MARKET = MarketType.COUPANG;

	@BeforeEach
	void setup() {
		tx = new TransactionTemplate(transactions);
		reset(source, policies, priceResolver, clients, prices);
		for (String table : List.of("sb_supplier_batch_retry", "sb_supplier_batch_attempt", "sb_supplier_batch_stage",
			"sb_supplier_batch_item", "sb_supplier_batch_run", "sb_product_change_target", "sb_product_change_history",
			"sb_product_edit_review", "sb_product_source_review", "sb_product_source_snapshot",
			"sb_product_source_collection",
			"sb_market_price_attempt", "sb_market_price_task", "sb_market_price_review", "sb_market_stock_attempt",
			"sb_market_stock_task", "sb_market_stock_review", "sb_market_inspection_gate"))
			jdbc.update("delete from " + table);
		lanes.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		for (String lane : List.of("PRICE_STOCK", "SOURCE_IHB", "SOURCE_VTB", "SOURCE_FTN", "SOURCE_COK", "SOURCE_OCD"))
			lanes.saveAndFlush(new ProductContentLane(lane));
		when(policies.find(VendorType.IHB)).thenReturn(Optional.of(VendorPricePolicy.builder().vendor(VendorType.IHB)
			.shipCurrency("KRW").shipBaseAmount(BigDecimal.ZERO).build()));
		when(source.fetch(any(), anyString())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new Observed(n("12000"), BigDecimal.ONE, "KRW", StockStatus.OUT_OF_STOCK, null, List.of());
		});
		when(priceResolver.explainForProduct(any(), any(), any())).thenReturn(new MarketSalePriceResolver.Explanation(
			MarketSalePriceResolver.Basis.CALCULATED, SalePriceRounding.fromPrice(n("18000"), n("15000"))));
		client = mock(MarketClient.class);
		when(client.inspectionAccountReference()).thenReturn("fixed-account");
		when(clients.hasClient(any())).thenReturn(true);
		when(clients.getClient(any())).thenReturn(client);
		externalPrice = new AtomicReference<>(n("10000"));
		externalQuantity = new AtomicInteger(999);
		acceptPrice = new AtomicBoolean(true);
		when(client.readSalePrice("123", "456")).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new MarketPriceRead(externalPrice.get(), true, "fixture", "fixed-account");
		});
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			if (acceptPrice.get())
				externalPrice.set(call.getArgument(2));
			return null;
		}).when(client).writeSalePrice(eq("123"), eq("456"), any());
		when(client.readStockQuantity(eq("123"), eq("456"), anyString())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new MarketStockRead(externalQuantity.get(), true, "fixture", "fixed-account", "456");
		});
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			call.<Runnable>getArgument(5).run();
			externalQuantity.set(call.getArgument(3));
			return null;
		}).when(client).writeStockQuantity(eq("123"), eq("456"), anyString(), anyInt(), eq("fixed-account"), any());
		product = tx.execute(status -> {
			var p = Product.create("SB-" + UUID.randomUUID(), new ProductCreateCommand(
				"https://kr.iherb.com/pr/example/12345", n("10000"), "테스트 상품", "Original", "브랜드", "US", n("0.3"),
				n("25"), MeasureUnit.G, List.of(), List.of(), "상세", "FOOD", true, 3, n("20"), VendorType.IHB, null));
			p.update(ProductUpdateCommand.builder().salePrice(n("10000")).stock(77).build());
			return products.saveAndFlush(p);
		});
		registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId()).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"123\",\"vendorItemId\":\"456\"}").build());
	}

	@Test
	void onlyFailedPriceRetriesWhileSourceDatabaseAndSuccessfulQuantityRemainUntouched() {
		acceptPrice.set(false);
		String run = savedRun(Mode.PRICE_STOCK);
		drain(run);
		var failed = market(run, Field.PRICE);
		var succeeded = market(run, Field.STOCK);
		assertThat(failed.getState()).isEqualTo("FAILED");
		assertThat(failed.isRetryable()).isTrue();
		assertThat(succeeded.getState()).isEqualTo("SUCCEEDED");
		var stockBefore = stockTasks.findById(succeeded.getTaskId()).orElseThrow();
		int stockReads = stockBefore.getReads(), stockWrites = stockBefore.getWrites();
		long revision = products.findById(product.getId()).orElseThrow().getRevision();
		String firstPriceReview = failed.getReferenceId(), stockReview = succeeded.getReferenceId();
		acceptPrice.set(true);
		var retry = new RetryRequest(UUID.randomUUID().toString(), item(run).id(), Step.MARKET, MARKET, Field.PRICE);
		batches.retry(run, retry, "admin");
		batches.retry(run, retry, "admin");
		drain(run);
		assertThat(batches.get(run).succeeded()).isEqualTo(1);
		assertThat(market(run, Field.PRICE).getState()).isEqualTo("SUCCEEDED");
		assertThat(market(run, Field.PRICE).getReferenceId()).isNotEqualTo(firstPriceReview);
		assertThat(market(run, Field.STOCK).getReferenceId()).isEqualTo(stockReview);
		assertThat(priceTasks.count()).isEqualTo(2);
		assertThat(stockTasks.count()).isEqualTo(1);
		var stockAfter = stockTasks.findById(succeeded.getTaskId()).orElseThrow();
		assertThat(stockAfter.getReads()).isEqualTo(stockReads);
		assertThat(stockAfter.getWrites()).isEqualTo(stockWrites);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(collections.count()).isEqualTo(1);
		assertThat(products.findById(product.getId()).orElseThrow().getRevision()).isEqualTo(revision);
		verify(source, times(1)).fetch(any(), anyString());
		verify(client, times(1)).writeStockQuantity(any(), any(), any(), anyInt(), any(), any());
		assertThat(targets.findAll()).extracting(ProductChangeTarget::getState)
			.containsExactlyInAnyOrder("CONFIRMED_PRICE", "CONFIRMED_QUANTITY");
	}

	@Test
	void committedChildWithLostResponseKeepsReviewAndDoesNotCreateAnotherTask() {
		String run = savedRun(Mode.PRICE);
		prepared(run, Field.PRICE);
		String reference = market(run, Field.PRICE).getReferenceId();
		var throwOnce = new AtomicBoolean(true);
		doAnswer(call -> {
			Object result = call.callRealMethod();
			if (throwOnce.getAndSet(false))
				throw new IllegalStateException("commit response lost");
			return result;
		}).when(prices).commit(reference, "admin");
		step(run);
		assertThat(market(run, Field.PRICE).getReferenceId()).isEqualTo(reference);
		assertThat(market(run, Field.PRICE).getState()).isEqualTo("RUNNING");
		assertThat(market(run, Field.PRICE).isRetryable()).isFalse();
		assertThat(priceTasks.findByReviewIdOrderById(reference)).hasSize(1);
		drain(run);
		assertThat(market(run, Field.PRICE).getState()).isEqualTo("SUCCEEDED");
		assertThat(market(run, Field.PRICE).getReferenceId()).isEqualTo(reference);
		assertThat(priceTasks.count()).isEqualTo(1);
		verify(prices, times(1)).commit(reference, "admin");
		verify(client, times(1)).writeSalePrice(any(), any(), any());
		assertThat(histories.count()).isEqualTo(1);
	}

	@Test
	void childReadFailurePreservesOwnershipAndPauseBlocksItsExternalRequests() {
		String run = savedRun(Mode.PRICE);
		queued(run, Field.PRICE);
		String reference = market(run, Field.PRICE).getReferenceId();
		doThrow(new IllegalStateException("temporary review read failure")).when(prices).get(reference);
		step(run);
		var waiting = market(run, Field.PRICE);
		assertThat(waiting.getState()).isEqualTo("RUNNING");
		assertThat(waiting.getReferenceId()).isEqualTo(reference);
		assertThat(waiting.isRetryable()).isFalse();
		batches.pause(run, "admin");
		openMarket();
		prices.processOne(MARKET);
		verify(client, never()).readSalePrice(any(), any());
		batches.retry(run, new RetryRequest(UUID.randomUUID().toString(), item(run).id(), Step.MARKET, MARKET,
			Field.PRICE), "admin");
		assertThat(market(run, Field.PRICE).getReferenceId()).isEqualTo(reference);
		doCallRealMethod().when(prices).get(reference);
		batches.resume(run, "admin");
		drain(run);
		assertThat(market(run, Field.PRICE).getState()).isEqualTo("SUCCEEDED");
		assertThat(priceTasks.count()).isEqualTo(1);
	}

	@Test
	void lateBatchFailureCannotDowngradeTargetSupersededByANewerManualConfirmation() {
		String run = savedRun(Mode.PRICE);
		queued(run, Field.PRICE);
		Long targetId = targets.findAll().stream().filter(ProductChangeTarget::isBatchManaged)
			.findFirst().orElseThrow().getId();
		var current = products.findById(product.getId()).orElseThrow();
		var edit = edits.previewSingle(current.getId(), current.getRevision(),
			mapper.createObjectNode().put("salePrice", 19000), "admin");
		assertThat(edits.commit(edit.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		openMarket();
		prices.processOne(MARKET);
		assertThat(priceTasks.findAll().getFirst().getState()).isEqualTo("STALE");
		var manual = prices.commit(prices.preview(List.of(product.getId()), Set.of(MARKET), "admin").id(), "admin");
		for (int i = 0; i < 3; i++) {
			openMarket();
			prices.processOne(MARKET);
		}
		assertThat(prices.get(manual.id()).items().getFirst().state()).isEqualTo("CONFIRMED_PRICE");
		assertThat(targets.findById(targetId).orElseThrow().getState()).isEqualTo("SUPERSEDED_BY_CURRENT");
		step(run);
		assertThat(market(run, Field.PRICE).getState()).isEqualTo("BLOCKED");
		assertThat(targets.findById(targetId).orElseThrow().getState()).isEqualTo("SUPERSEDED_BY_CURRENT");
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void expiredUncommittedMarketDraftIsRepreparedAfterLongPauseWithoutRepeatingSourceOrDatabase(Field field) {
		String run = savedRun(field == Field.PRICE ? Mode.PRICE : Mode.STOCK);
		prepared(run, field);
		String previous = market(run, field).getReferenceId();
		batches.pause(run, "admin");
		jdbc.update("update " + (field == Field.PRICE ? "sb_market_price_review" : "sb_market_stock_review")
			+ " set expires_at=? where id=?", Instant.now().minusSeconds(1), previous);
		step(run);
		assertThat(market(run, field).getReferenceId()).isEqualTo(previous);
		batches.resume(run, "admin");
		step(run);
		assertThat(market(run, field).getReferenceId()).isNotEqualTo(previous);
		assertThat(priceTasks.count() + stockTasks.count()).isZero();
		drain(run);
		assertThat(market(run, field).getState()).isEqualTo("SUCCEEDED");
		assertThat(priceTasks.count() + stockTasks.count()).isEqualTo(1);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(collections.count()).isEqualTo(1);
		verify(source, times(1)).fetch(any(), anyString());
	}

	@Test
	void expiryDoesNotReplaceAnAlreadyCommittedChildAfterPause() {
		String run = savedRun(Mode.PRICE);
		queued(run, Field.PRICE);
		String reference = market(run, Field.PRICE).getReferenceId();
		batches.pause(run, "admin");
		jdbc.update("update sb_market_price_review set expires_at=? where id=?", Instant.now().minusSeconds(1),
			reference);
		batches.resume(run, "admin");
		step(run);
		assertThat(market(run, Field.PRICE).getReferenceId()).isEqualTo(reference);
		assertThat(priceTasks.count()).isEqualTo(1);
		drain(run);
		assertThat(market(run, Field.PRICE).getState()).isEqualTo("SUCCEEDED");
		assertThat(priceReviews.count()).isEqualTo(1);
	}

	private String savedRun(Mode mode) {
		String id = batches.create(new CreateRequest(UUID.randomUUID().toString(), VendorType.IHB, mode,
			n("10"), n("20"), n("1500"), Set.of(MARKET)), "admin").id();
		for (int i = 0; i < 12 && !db(id).getState().equals("SUCCEEDED"); i++) {
			step(id);
			sourceWorker.tick();
		}
		assertThat(db(id).getState()).isEqualTo("SUCCEEDED");
		assertThat(histories.count()).isEqualTo(1);
		return id;
	}

	private void prepared(String run, Field field) {
		for (int i = 0; i < 12 && market(run, field).getReferenceId() == null; i++)
			step(run);
		assertThat(market(run, field).getReferenceId()).isNotNull();
	}

	private void queued(String run, Field field) {
		prepared(run, field);
		for (int i = 0; i < 12 && (field == Field.PRICE ? priceTasks.count() : stockTasks.count()) == 0; i++)
			step(run);
		assertThat(field == Field.PRICE ? priceTasks.count() : stockTasks.count()).isEqualTo(1);
	}

	private void drain(String run) {
		for (int i = 0; i < 60 && !batches.get(run).state().equals("COMPLETED"); i++) {
			step(run);
			openMarket();
			prices.processOne(MARKET);
			openMarket();
			stocks.processOne(MARKET);
		}
		assertThat(batches.get(run).state()).isEqualTo("COMPLETED");
	}

	private Item item(String run) {
		return batches.items(run, 0, 50, "", "ALL").getContent().getFirst();
	}

	private ProductSupplierBatchStage db(String run) {
		return stages.findByItemIdOrderById(item(run).id()).stream().filter(s -> s.getStage().equals("DB"))
			.findFirst().orElseThrow();
	}

	private ProductSupplierBatchStage market(String run, Field field) {
		return stages.findByItemIdOrderById(item(run).id()).stream().filter(s -> s.getStage().equals("MARKET")
			&& s.getMarket().equals(MARKET.name()) && s.getField().equals(field.name())).findFirst().orElseThrow();
	}

	private void step(String run) {
		jdbc.update("update sb_supplier_batch_stage set next_run_at=? where state in ('WAITING','RUNNING')",
			Instant.now().minusSeconds(1));
		jdbc.update("update sb_product_content_lane set next_allowed_at=?", Instant.now().minusSeconds(1));
		runner.process(run);
	}

	private void openMarket() {
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=?", Instant.now().minusSeconds(1));
		jdbc.update("update sb_market_price_task set next_run_at=?", Instant.now().minusSeconds(1));
		jdbc.update("update sb_market_stock_task set next_run_at=?", Instant.now().minusSeconds(1));
	}

	private static BigDecimal n(String value) {
		return new BigDecimal(value);
	}
}
