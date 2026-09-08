package com.sbshop.agent.core.application.product.edit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.*;
import com.sbshop.agent.core.domain.product.edit.*;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.core.domain.product.service.SalePriceRounding;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:productedit;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductEditServiceIntegrationTest.TestApp.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductEditServiceIntegrationTest {
	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		com.sbshop.agent.core.domain.market.inspection.MarketInspectionGateRepository.class})
	@Import({ProductEditService.class, ProductEditPlanner.class, ProductEditPolicy.class,
		com.sbshop.agent.core.application.market.MarketConnectionService.class,
		com.sbshop.agent.core.application.product.MarketRegistrationTxService.class})
	static class TestApp {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}

	@Autowired
	ProductEditService edits;
	@Autowired
	ProductEditPlanner planner;
	@Autowired
	com.sbshop.agent.core.application.market.MarketConnectionService connections;
	@MockitoSpyBean
	com.sbshop.agent.core.domain.market.repository.MarketConnectionEventRepository connectionEvents;
	@MockitoBean
	com.sbshop.agent.core.domain.market.client.MarketClientRouter marketClients;
	@Autowired
	com.sbshop.agent.core.application.product.MarketRegistrationTxService registrationTx;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@MockitoSpyBean
	ProductChangeHistoryRepository histories;
	@Autowired
	ProductEditReviewRepository reviews;
	@Autowired
	PlatformTransactionManager transactions;
	@Autowired
	EntityManager em;
	@Autowired
	ObjectMapper mapper;
	@MockitoBean
	MarketSalePriceResolver prices;
	@MockitoSpyBean
	ProductChangeTargetRepository targets;
	TransactionTemplate tx;

	@BeforeEach
	void before() {
		tx = new TransactionTemplate(transactions);
		connectionEvents.deleteAll();
		targets.deleteAll();
		histories.deleteAll();
		reviews.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		quote("12340");
	}

	void quote(String floor) {
        when(prices.explainForProduct(any(), any(), any())).thenReturn(new MarketSalePriceResolver.Explanation(
            MarketSalePriceResolver.Basis.CALCULATED, SalePriceRounding.fromPrice(new BigDecimal("18000"), new BigDecimal(floor))));
    }

	@Test
	void approvedBatchStoresSourceAndFixedPolicyInOneRevisionWithoutNormalDispatch() {
		Product product = create();
		link(product, MarketType.COUPANG, "{\"sellerProductId\":\"45\",\"vendorItemId\":\"46\"}");
		var collected = java.time.Instant.now().minusSeconds(60);
		var plan = batchPlan(product, batchValues());
		assertThat(plan.state()).isEqualTo(ProductEditPlanner.State.READY);
		assertThat(plan.command().salePrice()).isEqualByComparingTo("18000");
		String reviewId = UUID.randomUUID().toString();
		var result = tx.execute(s -> edits.commitReviewedBatchSource(reviewId, "admin",
			java.time.Instant.now().plusSeconds(1200), collected, true, plan));
		assertThat(result.state()).isEqualTo("SAVED");
		Product after = products.findById(product.getId()).orElseThrow();
		assertThat(after.getRevision()).isEqualTo(product.getRevision() + 1);
		assertThat(after.getPriceInfo().getMarginRate()).isEqualByComparingTo("10");
		assertThat(after.getPriceInfo().getCouponRate()).isEqualByComparingTo("20");
		assertThat(after.getPriceInfo().getMinMarginPrice()).isEqualByComparingTo("1500");
		assertThat(after.getPriceInfo().getCostPrice()).isEqualByComparingTo("11000");
		assertThat(after.getPriceInfo().getExchangeRate()).isEqualByComparingTo("1350.12");
		assertThat(after.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
		assertThat(after.getLogisticsInfo().getStock()).isZero();
		assertThat(after.getSalesQuantity()).isEqualTo(product.getSalesQuantity());
		assertThat(after.getProductName()).isEqualTo(product.getProductName());
		assertThat(after.getLogisticsInfo().getBundleQuantity()).isEqualTo(3);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(targets.findAll()).hasSize(2).allSatisfy(target -> {
			assertThat(target.isBatchManaged()).isTrue();
			assertThat(target.getState()).isEqualTo("BATCH_MANAGED");
			assertThat(target.getProductRevision()).isEqualTo(after.getRevision());
		});
		assertThat(targets.findTop50ByStateOrderById("PENDING_DISPATCH")).isEmpty();
		assertThat(targets.countPending(List.of(product.getId()))).singleElement()
			.satisfies(row -> assertThat(((Number)row[1]).longValue()).isEqualTo(2));
		assertThat(tx.execute(s -> edits.commitReviewedBatchSource(reviewId, "admin",
			java.time.Instant.now().minusSeconds(1), collected, true, plan)).historyId()).isEqualTo(result.historyId());
		assertThat(histories.count()).isEqualTo(1);
		assertThat(products.findById(product.getId()).orElseThrow().getRevision()).isEqualTo(after.getRevision());
	}

	@Test
	void batchTargetFailureRollsBackSourcePolicyHistoryAndCanReuseTheSamePlan() {
		Product product = create();
		link(product, MarketType.COUPANG, "{\"sellerProductId\":\"45\",\"vendorItemId\":\"46\"}");
		var plan = batchPlan(product, batchValues());
		String reviewId = UUID.randomUUID().toString();
		doThrow(new IllegalStateException("batch target storage unavailable")).when(targets).save(any());
		assertThatThrownBy(() -> tx.execute(s -> edits.commitReviewedBatchSource(reviewId, "admin",
			java.time.Instant.now().plusSeconds(1200), java.time.Instant.now().minusSeconds(60), true, plan)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(products.findById(product.getId()).orElseThrow().getRevision()).isEqualTo(product.getRevision());
		assertThat(products.findById(product.getId()).orElseThrow().getPriceInfo().getCostPrice())
			.isEqualByComparingTo("10000");
		assertThat(histories.count()).isZero();
		assertThat(targets.count()).isZero();
		reset(targets);
		assertThat(tx.execute(s -> edits.commitReviewedBatchSource(reviewId, "admin",
			java.time.Instant.now().plusSeconds(1200), java.time.Instant.now().minusSeconds(60), true, plan)).state())
			.isEqualTo("SAVED");
	}

	@Test
	void batchPlanCannotBroadenOrdinarySourceOrChangeNameAndSalesQuantity() {
		Product product = create();
		assertThatThrownBy(() -> planner.planSourceObservation(product, batchValues(), List.of()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("허용되지 않은 필드");
		for (String field : List.of("name", "salesQuantity", "bundleQuantity", "sourceUrl")) {
			var input = batchValues().put(field, "7");
			assertThatThrownBy(() -> planner.planBatchSourceObservation(product, input, List.of()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
		}
	}

	@Test
	void unchangedBatchStillRecordsReviewedCollectionOnceAndKeepsUnknownStock() {
		Product product = create();
		Long id = product.getId();
		tx.executeWithoutResult(s -> products.findById(id).orElseThrow().recordCrawlFailure("old failure"));
		product = products.findById(id).orElseThrow();
		var plan = batchPlan(product, mapper.createObjectNode().put("stockStatus", product.getStockStatus().name()));
		assertThat(plan.state()).isEqualTo(ProductEditPlanner.State.UNCHANGED);
		var collected = java.time.Instant.now().minusSeconds(60);
		String reviewId = UUID.randomUUID().toString();
		var result = tx.execute(s -> edits.commitReviewedBatchSource(reviewId, "admin",
			java.time.Instant.now().plusSeconds(1200), collected, true, plan));
		Product after = products.findById(id).orElseThrow();
		assertThat(result.state()).isEqualTo("SAVED");
		assertThat(after.getLastCrawlError()).isNull();
		assertThat(after.getLastCrawlAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
			.isEqualTo(java.time.LocalDateTime.ofInstant(collected, java.time.ZoneId.systemDefault())
				.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
		assertThat(after.getLogisticsInfo().getStock()).isEqualTo(3);
		assertThat(after.getSalePrice()).isEqualByComparingTo("20000");
		assertThat(targets.count()).isZero();
		assertThat(histories.findAll()).singleElement()
			.satisfies(history -> assertThat(history.getChanges()).isEqualTo("[]"));
		assertThat(tx.execute(s -> edits.commitReviewedBatchSource(reviewId, "admin",
			java.time.Instant.now().plusSeconds(1200), collected, true, plan)).historyId())
			.isEqualTo(result.historyId());
		assertThat(products.findById(id).orElseThrow().getRevision()).isEqualTo(after.getRevision());
	}

	@Test
	void batchRechecksRevisionConnectionPricePolicyAndCollectionTime() {
		for (String conflict : List.of("revision", "connection", "quote", "expiry", "future")) {
			Product product = create();
			var reg = link(product, MarketType.COUPANG, "{\"sellerProductId\":\"45\",\"vendorItemId\":\"46\"}");
			var plan = batchPlan(product, batchValues());
			if (conflict.equals("revision"))
				tx.executeWithoutResult(s -> products.findById(product.getId()).orElseThrow()
					.update(ProductUpdateCommand.builder().memo("concurrent edit").build()));
			if (conflict.equals("connection"))
				tx.executeWithoutResult(s -> registrations.findById(reg.getId()).orElseThrow()
					.enrichIdentifier("sellerProductId", "99"));
			if (conflict.equals("quote"))
				quote("19000");
			var expiry = java.time.Instant.now().plusSeconds(conflict.equals("expiry") ? -1 : 1200);
			var collected = java.time.Instant.now().plusSeconds(conflict.equals("future") ? 60 : -60);
			assertThatThrownBy(() -> tx.execute(s -> edits.commitReviewedBatchSource(UUID.randomUUID().toString(),
				"admin", expiry, collected, true, plan))).isInstanceOf(ProductEditConflictException.class);
			quote("12340");
		}
		assertThat(histories.count()).isZero();
	}

	@Test
	void batchRecomputesBaselineEvenWhenApprovedPriceInputsHaveNotChanged() {
		Product product = create();
		Long id = product.getId();
		tx.executeWithoutResult(s -> products.findById(id).orElseThrow()
			.update(mapper.convertValue(batchValues(), ProductUpdateCommand.class)));
		product = products.findById(id).orElseThrow();
		var plan = batchPlan(product, batchValues());
		assertThat(plan.changes()).extracting(ProductEditPlanner.Change::field).containsExactly("salePrice");
		assertThat(plan.command().marginRate()).isEqualByComparingTo("10");
		assertThat(tx.execute(s -> edits.commitReviewedBatchSource(UUID.randomUUID().toString(), "admin",
			java.time.Instant.now().plusSeconds(1200), java.time.Instant.now().minusSeconds(60), true, plan)).state())
			.isEqualTo("SAVED");
	}

	@Test
	void batchOwnershipSurvivesDispatchRetryAndCannotReturnToAutomaticDispatch() {
		var target = new ProductChangeTarget(1L, 2L, 3L, 4L, "COUPANG", "{}");
		target.manageByBatch();
		target.dispatchedToPrice(5L);
		target.priceOutcome("PENDING_DISPATCH");
		assertThat(target.isBatchManaged()).isTrue();
		assertThat(target.getState()).isEqualTo("BATCH_MANAGED");
		target.cancelForDetachedConnection();
		assertThat(target.getState()).isEqualTo("CANCELLED_DETACHED");
		assertThatThrownBy(target::manageByBatch).isInstanceOf(IllegalStateException.class);
	}

	private com.fasterxml.jackson.databind.node.ObjectNode batchValues() {
		return mapper.createObjectNode().put("costPrice", 11000).put("exchangeRate", new BigDecimal("1350.12"))
			.put("marginRate", 10).put("couponRate", 20).put("minMarginPrice", 1500)
			.put("stockStatus", "OUT_OF_STOCK").put("stock", 0);
	}

	private ProductEditPlanner.Plan batchPlan(Product product, com.fasterxml.jackson.databind.node.ObjectNode values) {
		return planner.planBatchSourceObservation(product, values, registrations.findByProductId(product.getId()));
	}

	Product create() {
		return tx.execute(s -> {
			Product p = Product.create("SB-" + UUID.randomUUID(),
				new ProductCreateCommand("https://example.com/item", new BigDecimal("10000"), "기본명", "Original", "브랜드",
					"US", new BigDecimal("0.3"), new BigDecimal("25.5"), MeasureUnit.G, List.of(), List.of(), "html",
					"FOOD", true, 3, new BigDecimal("20"), VendorType.IHB, null));
			p.update(ProductUpdateCommand.builder().salePrice(new BigDecimal("20000")).stock(3).build());
			return products.saveAndFlush(p);
		});
	}

	MarketRegistration link(Product p, MarketType type, String ids) {
		return registrations.saveAndFlush(MarketRegistration.builder().productId(p.getId()).sbProductId(p.getId())
			.marketType(type).marketIdentifiers(ids).marketDetailedInfo("{}").build());
	}

	ProductNumericPreviewUseCase.Request request(List<Product> ps, ProductNumericField field, String value) {
		return new ProductNumericPreviewUseCase.Request(ps.stream().map(Product::getId).toList(),
			List.of(new NumericChange(field, NumericChange.Operation.SET, new BigDecimal(value))), null);
	}

	@Test
	void smartstoreMixedPriceQuantityAndHtmlSaveOnceAndDispatchThreeIndependentTargets() throws Exception {
		var p = create();
		link(p, MarketType.SMART_STORE, "{\"originProductNo\":\"123\"}");
		var values = mapper.createObjectNode().put("salePrice", 18000).put("salesQuantity", 4).put("detailHtml",
			"<p>최신 상세</p>");
		var review = edits.previewSingle(p.getId(), p.getRevision(), values, "admin");
		assertThat(review.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.READY);
		var saved = edits.commit(review.reviewId(), "admin").items().getFirst();
		assertThat(saved.state()).isEqualTo("SAVED");
		assertThat(targets.findAll()).hasSize(3)
			.allSatisfy(t -> assertThat(t.getHistoryId()).isEqualTo(saved.historyId()));
		var groups = new java.util.HashSet<java.util.Set<String>>();
		for (var target : targets.findAll()) {
			var fields = new java.util.HashSet<String>();
			mapper.readTree(target.getSnapshot()).path("changes").forEach(c -> fields.add(c.path("field").asText()));
			groups.add(fields);
		}
		assertThat(groups).containsExactlyInAnyOrder(java.util.Set.of("salePrice"), java.util.Set.of("salesQuantity"),
			java.util.Set.of("detailHtml"));
		assertThat(products.findById(p.getId()).orElseThrow().getRevision()).isEqualTo(p.getRevision() + 1);
	}

	@Test
	void bulkValuesKeepLockedProductsAtomicAndStoreOnlyExplicitChanges() {
		Product locked = create(), editable = create();
		link(locked, MarketType.COUPANG, "{\"sellerProductId\":\"45\"}");
		var request = new ProductBulkValuesRequest(List.of(locked.getId(), editable.getId(), Long.MAX_VALUE),
			mapper.createObjectNode().put("category", "COSMETICS").put("memo", "검토한 공통 메모"));
		var review = edits.previewValues(request, "admin");
		assertThat(review.items()).extracting(ProductEditPlanner.Plan::state).containsExactly(
			ProductEditPlanner.State.EXCLUDED, ProductEditPlanner.State.READY, ProductEditPlanner.State.NOT_FOUND);
		assertThat(products.findById(editable.getId()).orElseThrow().getMemo()).isEqualTo(editable.getMemo());
		var result = edits.commit(review.reviewId(), "admin");
		assertThat(result.items()).extracting(ProductEditService.CommitItem::state).containsExactly("EXCLUDED", "SAVED",
			"NOT_FOUND");
		Product after = products.findById(editable.getId()).orElseThrow();
		assertThat(after.getCategory()).isEqualTo(ProductCategory.COSMETICS);
		assertThat(after.getMemo()).isEqualTo("검토한 공통 메모");
		assertThat(after.getBrand()).isEqualTo(editable.getBrand());
		assertThat(after.getDetailHtml()).isEqualTo(editable.getDetailHtml());
		assertThat(products.findById(locked.getId()).orElseThrow().getMemo()).isEqualTo(locked.getMemo());
		assertThat(edits.commit(review.reviewId(), "admin").items().get(1).historyId())
			.isEqualTo(result.items().get(1).historyId());
		assertThat(histories.count()).isEqualTo(1);
		assertThat(edits.history(editable.getId()).getFirst().changes()).extracting(ProductEditPlanner.Change::field)
			.containsExactlyInAnyOrder("category", "memo");
	}

	@Test
	void bulkBrandAndUnitUseExistingNameCompositionAndPreserveOtherFields() {
		Product product = create();
		var review = edits.previewValues(new ProductBulkValuesRequest(List.of(product.getId()),
			mapper.createObjectNode().put("brand", "새 브랜드").put("measureUnit", "ML")), "admin");
		assertThat(review.items().getFirst().changes()).anySatisfy(change -> {
			assertThat(change.field()).isEqualTo("name");
			assertThat(change.derived()).isTrue();
			assertThat(change.after()).contains("새 브랜드", "25.5밀리리터", "3개");
		});
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		assertThat(products.findById(product.getId()).orElseThrow().getSalePrice())
			.isEqualByComparingTo(product.getSalePrice());
		assertThat(histories.findAll().getFirst().getReviewDetails()).contains("새 브랜드", "derived");
	}

	@Test
	void bulkValuesEnforceActorRevisionAndExpiryThroughExistingCommit() {
		Product changed = create(), editable = create();
		var review = edits.previewValues(new ProductBulkValuesRequest(List.of(changed.getId(), editable.getId()),
			mapper.createObjectNode().put("memo", "새 메모")), "admin");
		assertThatThrownBy(() -> edits.commit(review.reviewId(), "another-admin"))
			.isInstanceOf(ProductEditConflictException.class);
		tx.executeWithoutResult(s -> products.findById(changed.getId()).orElseThrow()
			.update(ProductUpdateCommand.builder().memo("다른 변경").build()));
		assertThat(edits.commit(review.reviewId(), "admin").items()).extracting(ProductEditService.CommitItem::state)
			.containsExactly("CONFLICT", "SAVED");
		Product expired = create();
		var old = edits.previewValues(
			new ProductBulkValuesRequest(List.of(expired.getId()), mapper.createObjectNode().put("memo", "만료 검토")),
			"admin");
		tx.executeWithoutResult(s -> org.springframework.test.util.ReflectionTestUtils.setField(
			reviews.findById(old.reviewId()).orElseThrow(), "expiresAt", java.time.Instant.now().minusSeconds(1)));
		assertThat(edits.commit(old.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
		assertThat(products.findById(expired.getId()).orElseThrow().getMemo()).isEqualTo(expired.getMemo());
	}

	@Test
	void bulkHistoryFailureRollsBackOnlyItsProductAndSameReviewRetriesSafely() {
		Product a = create(), b = create();
		var review = edits.previewValues(
			new ProductBulkValuesRequest(List.of(a.getId(), b.getId()), mapper.createObjectNode().put("memo", "일괄 메모")),
			"admin");
		doThrow(new IllegalStateException("fixture history storage failure")).when(histories)
			.save(argThat(h -> h.getProductId().equals(b.getId())));
		var first = edits.commit(review.reviewId(), "admin");
		assertThat(first.items()).extracting(ProductEditService.CommitItem::state).containsExactly("SAVED", "FAILED");
		assertThat(products.findById(b.getId()).orElseThrow().getMemo()).isEqualTo(b.getMemo());
		assertThat(histories.count()).isEqualTo(1);
		reset(histories);
		var retry = edits.commit(review.reviewId(), "admin");
		assertThat(retry.items()).extracting(ProductEditService.CommitItem::state).containsExactly("SAVED", "SAVED");
		assertThat(retry.items().getFirst().historyId()).isEqualTo(first.items().getFirst().historyId());
		assertThat(histories.count()).isEqualTo(2);
	}

	@Test
	void explicitEmptyMemoAndImageListClearOnlyReviewedFields() {
		Product product = create();
		tx.executeWithoutResult(
			s -> products.findById(product.getId()).orElseThrow().update(ProductUpdateCommand.builder()
				.memo("지울 메모").hostedImages(List.of("https://example.com/old.jpg")).build()));
		var values = mapper.createObjectNode().put("memo", "");
		values.putArray("hostedImages");
		var review = edits.previewValues(new ProductBulkValuesRequest(List.of(product.getId()), values), "admin");
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		Product after = products.findById(product.getId()).orElseThrow();
		assertThat(after.getMemo()).isEmpty();
		assertThat(after.getHostedImages()).isEmpty();
		assertThat(after.getDetailHtml()).isEqualTo(product.getDetailHtml());
	}

	@Test
	void oversizedBulkHtmlIsRejectedBeforeAnyReviewIsStored() {
		var ids = java.util.stream.LongStream.rangeClosed(1, 500).boxed().toList();
		assertThatThrownBy(() -> edits.previewValues(new ProductBulkValuesRequest(ids,
			mapper.createObjectNode().put("detailHtml", "x".repeat(1_000_000))), "admin"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("너무 큽니다");
		assertThat(reviews.count()).isZero();
		assertThat(histories.count()).isZero();
		assertThat(new ProductBulkValuesRequest(ids, mapper.createObjectNode().put("memo", "정상 공통 메모")).productIds())
			.hasSize(500);
	}

	@Test
	void bulkReviewStopsWhenBeforeValuesExceedTheReviewBudget() {
		var ids = new ArrayList<Long>();
		for (int i = 0; i < 6; i++) {
			Product product = create();
			ids.add(product.getId());
			tx.executeWithoutResult(s -> products.findById(product.getId()).orElseThrow().update(
				ProductUpdateCommand.builder().detailHtml("x".repeat(950_000)).build()));
		}
		assertThatThrownBy(() -> edits.previewValues(new ProductBulkValuesRequest(ids,
			mapper.createObjectNode().put("detailHtml", "짧은 새 설명")), "admin"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("너무 큽니다");
		assertThat(reviews.count()).isZero();
		assertThat(histories.count()).isZero();
	}

	@Test
	void previewProtectsMinimumAndCommitIsIdempotentWithDurablePendingMarkets() {
		Product p = create();
		link(p, MarketType.CAFE24, "{\"product_no\":\"42\",\"gmarket_goodsNo\":\"007\"}");
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.SALE_PRICE, "12345"), "admin");
		assertThat(review.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.READY);
		assertThat(review.items().getFirst().notices())
			.anySatisfy(n -> assertThat(n).contains("12345", "12300", "반올림"));
		assertThat(review.items().getFirst().changes()).anySatisfy(c -> {
			assertThat(c.field()).isEqualTo("salePrice");
			assertThat(c.after()).isEqualTo("12400");
		});
		assertThat(products.findById(p.getId()).orElseThrow().getSalePrice()).isEqualByComparingTo("20000");
		var first = edits.commit(review.reviewId(), "admin");
		assertThat(first.items().getFirst().state()).isEqualTo("SAVED");
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().historyId())
			.isEqualTo(first.items().getFirst().historyId());
		assertThat(products.findById(p.getId()).orElseThrow().getSalePrice()).isEqualByComparingTo("12400");
		assertThat(histories.count()).isEqualTo(1);
		assertThat(targets.findAll()).extracting(ProductChangeTarget::getMarket).containsExactlyInAnyOrder("CAFE24",
			"GMARKET");
		assertThat(targets.findAll()).allMatch(t -> t.getState().equals("PENDING_DISPATCH"));
		assertThat(targets.countPending(List.of(p.getId()))).singleElement().satisfies(row -> {
			assertThat(row[0]).isEqualTo(p.getId());
			assertThat(((Number)row[1]).longValue()).isEqualTo(2);
		});
		assertThat(histories.findAll().getFirst().getReviewDetails()).contains("12400", "12340");
		assertThat(edits.history(p.getId()).getFirst().changes()).hasSize(1);
	}

	@Test
	void concurrentRetryDoesNotApplyTwice() throws Exception {
		Product p = create();
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.STOCK, "4"), "admin");
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(() -> edits.commit(review.reviewId(), "admin"));
			var b = pool.submit(() -> edits.commit(review.reviewId(), "admin"));
			assertThat(a.get(10, TimeUnit.SECONDS).items().getFirst().state()).isEqualTo("SAVED");
			assertThat(b.get(10, TimeUnit.SECONDS).items().getFirst().state()).isEqualTo("SAVED");
		}
		assertThat(histories.count()).isEqualTo(1);
		assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(4);
	}

	@Test
	void mixedPriceAndSalesQuantityShareOneAtomicHistoryWithDisjointDispatchTargets() throws Exception {
		Product p = create();
		var reg = link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\",\"vendorItemId\":\"67\"}");
		var review = edits.previewSingle(p.getId(), p.getRevision(), mapper.createObjectNode()
			.put("salePrice", 18000).put("costPrice", 11000).put("salesQuantity", 42).put("memo", "함께 검토한 변경"),
			"admin");
		assertThat(review.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.READY);
		var saved = edits.commit(review.reviewId(), "admin").items().getFirst();
		assertThat(saved.state()).isEqualTo("SAVED");
		Product after = products.findById(p.getId()).orElseThrow();
		assertThat(after.getRevision()).isEqualTo(p.getRevision() + 1);
		assertThat(after.getSalePrice()).isEqualByComparingTo("18000");
		assertThat(after.getPriceInfo().getCostPrice()).isEqualByComparingTo("11000");
		assertThat(after.getSalesQuantity()).isEqualTo(42);
		assertThat(after.getMemo()).isEqualTo("함께 검토한 변경");
		assertThat(histories.findAll()).singleElement().satisfies(history -> {
			assertThat(history.getBeforeRevision()).isEqualTo(p.getRevision());
			assertThat(history.getAfterRevision()).isEqualTo(after.getRevision());
			assertThat(history.getChanges()).contains("salePrice", "costPrice", "salesQuantity", "memo");
		});
		var pending = targets.findAll();
		assertThat(pending).hasSize(2).allSatisfy(target -> {
			assertThat(target.getHistoryId()).isEqualTo(saved.historyId());
			assertThat(target.getProductRevision()).isEqualTo(after.getRevision());
			assertThat(target.getRegistrationId()).isEqualTo(reg.getId());
			assertThat(target.getMarket()).isEqualTo("COUPANG");
			assertThat(target.getState()).isEqualTo("PENDING_DISPATCH");
		});
		for (var target : pending) {
			var snapshot = mapper.readTree(target.getSnapshot());
			Set<String> commandKeys = new HashSet<>();
			snapshot.path("command").fieldNames().forEachRemaining(commandKeys::add);
			Set<String> changeKeys = new HashSet<>();
			snapshot.path("changes").forEach(change -> changeKeys.add(change.path("field").asText()));
			assertThat(commandKeys).isEqualTo(changeKeys);
			if (commandKeys.contains("salesQuantity")) {
				assertThat(commandKeys).containsExactly("salesQuantity");
				assertThat(snapshot.path("command").path("salesQuantity").asInt()).isEqualTo(42);
				assertThat(snapshot.path("prices")).isEmpty();
				assertThat(com.sbshop.agent.core.application.market.sync.MarketStockSyncService
					.handlesSavedQuantityTarget(target, mapper)).isTrue();
			} else {
				assertThat(commandKeys).containsExactlyInAnyOrder("salePrice", "costPrice");
				assertThat(snapshot.path("command").path("salePrice").asInt()).isEqualTo(18000);
				assertThat(snapshot.path("command").path("costPrice").asInt()).isEqualTo(11000);
				assertThat(snapshot.path("prices")).hasSize(4);
				assertThat(com.sbshop.agent.core.application.market.sync.MarketStockSyncService
					.handlesSavedQuantityTarget(target, mapper)).isFalse();
			}
		}
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().historyId())
			.isEqualTo(saved.historyId());
		assertThat(histories.count()).isEqualTo(1);
		assertThat(targets.count()).isEqualTo(2);
		assertThat(products.findById(p.getId()).orElseThrow().getRevision()).isEqualTo(after.getRevision());
	}

	@Test
	void numericBulkSalesQuantityRespectsMarketPolicyKeepsSourceStockAndSplitsPriceTargets() {
		Product coupang = create(), unlinked = create(), cafe24 = create();
		link(coupang, MarketType.COUPANG, "{\"sellerProductId\":\"45\",\"vendorItemId\":\"67\"}");
		link(cafe24, MarketType.CAFE24, "{\"product_no\":\"42\"}");
		var review = edits.previewNumeric(new ProductNumericPreviewUseCase.Request(
			List.of(coupang.getId(), unlinked.getId(), cafe24.getId()), List.of(
				new NumericChange(ProductNumericField.SALE_PRICE, NumericChange.Operation.SET, new BigDecimal("18000")),
				new NumericChange(ProductNumericField.SALES_QUANTITY, NumericChange.Operation.SET,
					new BigDecimal("4.8"))),
			null), "admin");
		assertThat(review.items()).extracting(ProductEditPlanner.Plan::state).containsExactly(
			ProductEditPlanner.State.READY, ProductEditPlanner.State.READY, ProductEditPlanner.State.EXCLUDED);
		assertThat(review.items().getFirst().notices())
			.anySatisfy(note -> assertThat(note).contains("4.8", "→ 4", "버림"));
		var saved = edits.commit(review.reviewId(), "admin");
		assertThat(saved.items()).extracting(ProductEditService.CommitItem::state).containsExactly("SAVED", "SAVED",
			"EXCLUDED");
		for (Product original : List.of(coupang, unlinked)) {
			Product after = products.findById(original.getId()).orElseThrow();
			assertThat(after.getSalesQuantity()).isEqualTo(4);
			assertThat(after.getStock()).isEqualTo(original.getStock());
			assertThat(after.getRevision()).isEqualTo(original.getRevision() + 1);
		}
		assertThat(products.findById(cafe24.getId()).orElseThrow().getSalesQuantity())
			.isEqualTo(cafe24.getSalesQuantity());
		assertThat(products.findById(cafe24.getId()).orElseThrow().getSalePrice())
			.isEqualByComparingTo(cafe24.getSalePrice());
		assertThat(targets.findAll()).hasSize(2).allMatch(target -> target.getProductId().equals(coupang.getId()));
		assertThat(targets.findAll().stream()
			.filter(target -> com.sbshop.agent.core.application.market.sync.MarketStockSyncService
				.handlesSavedQuantityTarget(target, mapper)))
			.hasSize(1);
		assertThat(edits.commit(review.reviewId(), "admin").items())
			.extracting(ProductEditService.CommitItem::historyId)
			.containsExactlyElementsOf(saved.items().stream().map(ProductEditService.CommitItem::historyId).toList());
		assertThat(histories.count()).isEqualTo(2);
		assertThat(targets.count()).isEqualTo(2);
	}

	@Test
	void secondSplitTargetFailureRollsBackBothFieldsHistoryAndFirstTarget() {
		Product p = create();
		link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\",\"vendorItemId\":\"67\"}");
		var review = edits.previewSingle(p.getId(), p.getRevision(), mapper.createObjectNode()
			.put("salePrice", 18000).put("salesQuantity", 42), "admin");
		doThrow(new IllegalStateException("second target storage failure")).when(targets)
			.save(argThat(target -> com.sbshop.agent.core.application.market.sync.MarketStockSyncService
				.handlesSavedQuantityTarget(target, mapper)));
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("FAILED");
		Product rolledBack = products.findById(p.getId()).orElseThrow();
		assertThat(rolledBack.getRevision()).isEqualTo(p.getRevision());
		assertThat(rolledBack.getSalePrice()).isEqualByComparingTo(p.getSalePrice());
		assertThat(rolledBack.getSalesQuantity()).isEqualTo(p.getSalesQuantity());
		assertThat(histories.count()).isZero();
		assertThat(targets.count()).isZero();
		reset(targets);
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		assertThat(histories.count()).isEqualTo(1);
		assertThat(targets.count()).isEqualTo(2);
	}

	@Test
	void priceOnlyAndQuantityOnlyEditsStillProduceOneTargetEach() throws Exception {
		Product price = create(), quantity = create();
		link(price, MarketType.COUPANG, "{\"sellerProductId\":\"45\",\"vendorItemId\":\"67\"}");
		link(quantity, MarketType.COUPANG, "{\"sellerProductId\":\"46\",\"vendorItemId\":\"68\"}");
		for (Product product : List.of(price, quantity)) {
			String field = product.getId().equals(price.getId()) ? "salePrice" : "salesQuantity";
			var review = edits.previewSingle(product.getId(), product.getRevision(), mapper.createObjectNode()
				.put(field, field.equals("salePrice") ? 18000 : 42).put("memo", "기존 단일 대상"), "admin");
			assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
			var target = targets.findByProductIdAndMarket(product.getId(), "COUPANG");
			assertThat(target).hasSize(1);
			assertThat(mapper.readTree(target.getFirst().getSnapshot()).path("changes")).hasSize(1);
			assertThat(products.findById(product.getId()).orElseThrow().getMemo()).isEqualTo("기존 단일 대상");
		}
	}

	@Test
	void unsupportedFieldInPriceQuantityEditStillExcludesTheWholeProduct() {
		Product p = create();
		link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\",\"vendorItemId\":\"67\"}");
		var review = edits.previewSingle(p.getId(), p.getRevision(), mapper.createObjectNode()
			.put("salePrice", 18000).put("salesQuantity", 42).put("stock", 10), "admin");
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("EXCLUDED");
		assertThat(products.findById(p.getId()).orElseThrow().getRevision()).isEqualTo(p.getRevision());
		assertThat(histories.count()).isZero();
		assertThat(targets.count()).isZero();
	}

	@Test
	void changedProductIsExcludedWhileOtherProductCommits() {
		Product a = create(), b = create();
		var review = edits.previewNumeric(request(List.of(a, b), ProductNumericField.STOCK, "4"), "admin");
		tx.executeWithoutResult(
			s -> products.findById(a.getId()).orElseThrow().update(ProductUpdateCommand.builder().stock(99).build()));
		assertThat(edits.commit(review.reviewId(), "admin").items()).extracting(ProductEditService.CommitItem::state)
			.containsExactly("CONFLICT", "SAVED");
		assertThat(products.findById(a.getId()).orElseThrow().getStock()).isEqualTo(99);
	}

	@Test
	void newRegistrationInvalidatesReviewedChanges() {
		Product p = create();
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.STOCK, "4"), "admin");
		link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\"}");
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
		assertThat(histories.count()).isZero();
	}

	@Test
	void legacyDeletionDoesNotUnlockCategoryAndUnchangedFieldsDoNotBlockMemo() {
		Product p = create();
		var reg = link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\"}");
		reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		registrations.saveAndFlush(reg);
		var review = edits.previewSingle(p.getId(), p.getRevision(),
			mapper.createObjectNode().put("category", "COSMETICS"), "admin");
		assertThat(review.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.EXCLUDED);
		assertThat(edits.workspace(p.getId()).fields()).anySatisfy(r -> {
			assertThat(r.field()).isEqualTo("category");
			assertThat(r.permission()).isEqualTo(ProductEditPolicy.Permission.LOCKED);
		});
		var memo = edits.previewSingle(p.getId(), p.getRevision(),
			mapper.createObjectNode().put("name", p.getProductName()).put("memo", "내부 기록"), "admin");
		var savedMemo = edits.commit(memo.reviewId(), "admin").items().getFirst();
		assertThat(savedMemo.state()).isEqualTo("SAVED");
		assertThat(savedMemo.reason()).isEqualTo("DB 저장 완료");
		assertThat(targets.count()).isZero();
	}

	@Test
	void quantityTruncationIsVisibleInFinalReviewAndStoredHistory() {
		Product p = create();
		var request = new ProductNumericPreviewUseCase.Request(List.of(p.getId()),
			List.of(
				new NumericChange(ProductNumericField.STOCK, NumericChange.Operation.PERCENT, new BigDecimal("50"))),
			null);
		var review = edits.previewNumeric(request, "admin");
		assertThat(review.items().getFirst().notices()).anySatisfy(n -> assertThat(n).contains("4.5", "→ 4", "버림"));
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(4);
		assertThat(histories.findAll().getFirst().getReviewDetails()).contains("4.5", "버림");
	}

	@Test
	void reservedRegistrationBlocksEvenPriceEditing() {
		Product p = create();
		link(p, MarketType.COUPANG, "{}");
		assertThat(edits.previewNumeric(request(List.of(p), ProductNumericField.SALE_PRICE, "18000"), "admin").items()
			.getFirst().state()).isEqualTo(ProductEditPlanner.State.EXCLUDED);
	}

	@Test
	void allFieldsOfAProductAreExcludedIfOneIsLocked() {
		Product p = create();
		link(p, MarketType.CAFE24, "{\"product_no\":\"42\"}");
		var r = new ProductNumericPreviewUseCase.Request(List.of(p.getId()), List.of(
			new NumericChange(ProductNumericField.SALE_PRICE, NumericChange.Operation.SET, new BigDecimal("18000")),
			new NumericChange(ProductNumericField.BUNDLE_QUANTITY, NumericChange.Operation.SET, new BigDecimal("5"))),
			null);
		var review = edits.previewNumeric(r, "admin");
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("EXCLUDED");
		assertThat(products.findById(p.getId()).orElseThrow().getSalePrice()).isEqualByComparingTo("20000");
	}

	@Test
	void targetFailureRollsBackProductAndHistoryAndRetryWorks() {
		Product p = create();
		link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\"}");
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.SALE_PRICE, "18000"), "admin");
		doThrow(new IllegalStateException("test storage failure")).when(targets).save(any());
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("FAILED");
		assertThat(products.findById(p.getId()).orElseThrow().getSalePrice()).isEqualByComparingTo("20000");
		assertThat(histories.count()).isZero();
		reset(targets);
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
	}

	@Test
	void pricePolicyChangeRequiresNewReview() {
		Product p = create();
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.SALE_PRICE, "12345"), "admin");
		quote("17000");
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
	}

	@Test
	void fractionalCapacityIsPreservedInDerivedNameAndHistory() {
		Product p = create();
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.CAPACITY, "10.5"), "admin");
		assertThat(review.items().getFirst().changes()).anySatisfy(c -> {
			assertThat(c.field()).isEqualTo("name");
			assertThat(c.after()).contains("10.5");
			assertThat(c.derived()).isTrue();
		});
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
	}

	@Test
	void registrationReservationChecksTheProductVersionBeforeCreatingAnyRow() {
		Product p = create();
		assertThatThrownBy(
			() -> registrationTx.savePending(p.getId(), MarketType.COUPANG, p.getProductName(), p.getRevision() + 1))
			.isInstanceOf(ProductEditConflictException.class);
		assertThat(registrations.count()).isZero();
		registrationTx.savePending(p.getId(), MarketType.COUPANG, p.getProductName(), p.getRevision());
		assertThat(edits.previewNumeric(request(List.of(p), ProductNumericField.STOCK, "4"), "admin").items().getFirst()
			.state()).isEqualTo(ProductEditPlanner.State.EXCLUDED);
	}

	@Test
	void sharedLegacyAndImagePathsCannotBypassMarketRestrictions() {
		Product p = create();
		link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\"}");
		assertThatThrownBy(() -> edits.saveExisting(p.getId(),
			ProductUpdateCommand.builder().category(ProductCategory.COSMETICS).memo("not partial").build(),
			p.getRevision(), "legacy"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> edits.requireWritable(p.getId(), List.of("hostedImages", "detailHtml")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(histories.count()).isZero();
	}

	@Test
	void approvedNameCompositionIsAvailableWithoutAFeatureFlag() {
		ProductEditPolicy defaultPolicy = new ProductEditPolicy();
		for (String field : List.of("brand", "baseName", "capacity", "measureUnit", "bundleQuantity"))
			assertThat(defaultPolicy.rule(field, List.of()).editable()).isTrue();
		assertThat(defaultPolicy.rule("memo", List.of()).editable()).isTrue();
	}

	@Test
	void confirmedBanDetachesOnlyItsChannelCancelsTargetsAndPreservesIdentifiers() {
		Product p = create();
		var reg = link(p, MarketType.CAFE24,
			"{\"product_no\":\"42\",\"gmarket_goodsNo\":\"007\",\"auction_goodsNo\":\"A88\"}");
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.SALE_PRICE, "18000"), "admin");
		edits.commit(review.reviewId(), "admin");
		var result = connections.confirmProhibition(p.getId(), reg.getId(), MarketType.GMARKET, reg.getRevision(),
			"007", "seller-g", "판매자센터 영구금지 확인", "admin");
		assertThat(result.result()).isEqualTo("DETACHED");
		var after = registrations.findById(reg.getId()).orElseThrow();
		assertThat(after.getConnectionState()).isEqualTo(MarketConnectionState.LINKED);
		assertThat(after.getGmarketConnectionState()).isEqualTo(MarketConnectionState.DETACHED_PROHIBITED);
		assertThat(after.getAuctionConnectionState()).isEqualTo(MarketConnectionState.LINKED);
		assertThat(after.getMarketIdentifiers()).isEqualTo(reg.getMarketIdentifiers());
		assertThat(targets.findByRegistrationIdAndMarketAndState(reg.getId(), "GMARKET", "CANCELLED_DETACHED"))
			.hasSize(1);
		assertThat(targets.findByRegistrationIdAndMarketAndState(reg.getId(), "AUCTION", "PENDING_DISPATCH"))
			.hasSize(1);
		assertThat(edits.workspace(p.getId()).connections()).extracting(ProductEditPolicy.Connection::market)
			.containsExactlyInAnyOrder("CAFE24", "AUCTION");
		assertThat(connectionEvents.findAll()).singleElement()
			.satisfies(e -> assertThat(e.getEvidence()).contains("seller-g", "판매자"));
		assertThat(after.connectionWriteBlock()).contains("하위 마켓");
	}

	@Test
	void finalDetachmentUnlocksEditingAndLateSuccessDoesNotReactivate() {
		Product p = create();
		var reg = link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\"}");
		connections.confirmProhibition(p.getId(), reg.getId(), MarketType.COUPANG, reg.getRevision(), "45", "seller",
			"금지 확인", "admin");
		assertThat(edits.workspace(p.getId()).connections()).isEmpty();
		var name = edits.previewSingle(p.getId(), p.getRevision(), mapper.createObjectNode().put("brand", "새 브랜드"),
			"admin");
		assertThat(edits.commit(name.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		var after = registrations.findById(reg.getId()).orElseThrow();
		after.markSynced();
		after.confirmPresentOnMarket();
		assertThat(after.getConnectionState()).isEqualTo(MarketConnectionState.DETACHED_PROHIBITED);
		assertThat(after.getIsSynced()).isFalse();
		assertThatThrownBy(() -> after.replaceIdentifiersArchivingPrevious("{\"sellerProductId\":\"46\"}"))
			.isInstanceOf(IllegalStateException.class);
		assertThat(products.findById(p.getId()).orElseThrow().isDeleted()).isFalse();
		reg.markSynced();
		assertThatThrownBy(() -> registrations.saveAndFlush(reg))
			.isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
	}

	@Test
	void observationFailureRollsBackDetachmentAndPendingCancellation() {
		Product p = create();
		var reg = link(p, MarketType.COUPANG, "{\"sellerProductId\":\"45\"}");
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.SALE_PRICE, "18000"), "admin");
		edits.commit(review.reviewId(), "admin");
		doThrow(new IllegalStateException("storage unavailable")).when(connectionEvents).save(any());
		assertThatThrownBy(() -> connections.confirmProhibition(p.getId(), reg.getId(), MarketType.COUPANG,
			reg.getRevision(), "45", "seller", "금지 확인", "admin")).isInstanceOf(IllegalStateException.class);
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.LINKED);
		assertThat(targets.findAll()).allMatch(t -> t.getState().equals("PENDING_DISPATCH"));
	}

	@Test
	void changedIdentifierDuringLookupKeepsNewListingConnectedAndStoresStaleEvidence() {
		Product p = create();
		var reg = link(p, MarketType.SMART_STORE, "{\"originProductNo\":\"45\"}");
		var adapter = mock(com.sbshop.agent.core.domain.market.client.MarketClient.class);
		when(marketClients.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClients.getClient(MarketType.SMART_STORE)).thenReturn(adapter);
		when(adapter.inspectListing("45")).thenAnswer(i -> {
			tx.executeWithoutResult(
				s -> registrations.findById(reg.getId()).orElseThrow().enrichIdentifier("originProductNo", "46"));
			return observation(com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.DELETED);
		});
		assertThat(connections.inspect(p.getId(), reg.getId(), MarketType.SMART_STORE, "admin").result())
			.isEqualTo("STALE");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.LINKED);
		assertThat(connectionEvents.findAll()).singleElement()
			.satisfies(e -> assertThat(e.getExternalId()).isEqualTo("45"));
	}

	@Test
	void stockoutAndUnknownKeepConnectionWhileConfirmedAbsenceDetaches() {
		Product p = create();
		var reg = link(p, MarketType.SMART_STORE, "{\"originProductNo\":\"45\"}");
		var adapter = mock(com.sbshop.agent.core.domain.market.client.MarketClient.class);
		when(marketClients.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClients.getClient(MarketType.SMART_STORE)).thenReturn(adapter);
		for (var state : List.of(
			com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.OUT_OF_STOCK,
			com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.UNKNOWN)) {
			when(adapter.inspectListing("45")).thenReturn(observation(state));
			assertThat(connections.inspect(p.getId(), reg.getId(), MarketType.SMART_STORE, "admin").state())
				.isEqualTo("LINKED");
		}
		when(adapter.inspectListing("45")).thenReturn(
			observation(com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.DELETED));
		assertThat(connections.inspect(p.getId(), reg.getId(), MarketType.SMART_STORE, "admin").state())
			.isEqualTo("DETACHED_DELETED");
		assertThat(connections.inspect(p.getId(), reg.getId(), MarketType.SMART_STORE, "admin").result())
			.isEqualTo("ALREADY_DETACHED");
		assertThat(connectionEvents.count()).isEqualTo(4);
	}

	private com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation observation(
		com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State state) {
		return new com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation(state, "TEST", "fixture",
			"seller", "GET fixture", java.time.Instant.now());
	}

	@Test
	void staleSingleReviewAndDifferentActorAreRejected() {
		Product p = create();
		assertThatThrownBy(() -> edits.previewSingle(p.getId(), p.getRevision() + 1,
			mapper.createObjectNode().put("memo", "x"), "admin")).isInstanceOf(ProductEditConflictException.class);
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.STOCK, "4"), "admin");
		assertThatThrownBy(() -> edits.commit(review.reviewId(), "other"))
			.isInstanceOf(ProductEditConflictException.class);
		assertThat(histories.count()).isZero();
	}

	@Test
	void expiredReviewCannotWriteButPreviouslySavedResultRemainsIdempotent() {
		Product p = create();
		var review = edits.previewNumeric(request(List.of(p), ProductNumericField.STOCK, "4"), "admin");
		tx.executeWithoutResult(
			s -> em.createQuery("update ProductEditReview r set r.expiresAt = :expired where r.id = :id")
				.setParameter("expired", java.time.Instant.now().minusSeconds(1)).setParameter("id", review.reviewId())
				.executeUpdate());
		assertThat(edits.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
	}

	@Test
	void salesQuantityDefaultsTo300AndFractionalEditKeepsLegacyStock() {
		var p = create();
		int beforeStock = p.getStock();
		assertThat(p.getSalesQuantity()).isEqualTo(300);
		var preview = edits.previewSingle(p.getId(), p.getRevision(),
			mapper.createObjectNode().put("salesQuantity", 4.8), "admin");
		assertThat(preview.items().getFirst().notices()).anyMatch(n -> n.contains("4.8") && n.contains("4"));
		assertThat(edits.commit(preview.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		var saved = products.findById(p.getId()).orElseThrow();
		assertThat(saved.getSalesQuantity()).isEqualTo(4);
		assertThat(saved.getStock()).isEqualTo(beforeStock);
	}
}
