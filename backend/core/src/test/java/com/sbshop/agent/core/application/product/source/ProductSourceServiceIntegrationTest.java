package com.sbshop.agent.core.application.product.source;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.application.product.batch.ProductSupplierBatchSource;
import com.sbshop.agent.core.application.product.batch.ProductSupplierBatchService;
import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.application.product.source.ProductSourceData.*;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.*;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:productsource;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductSourceServiceIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductSourceServiceIntegrationTest {
	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class})
	@Import({ProductSourceService.class, ProductSourceWorker.class, ProductContentWorker.class,
		ProductContentService.class, ProductEditService.class, ProductEditPlanner.class, ProductEditPolicy.class,
		ProductSupplierBatchSource.class})
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}

	@Autowired
	ProductSourceService service;
	@Autowired
	ProductSupplierBatchSource batchSource;
	@Autowired
	ProductSourceWorker worker;
	@Autowired
	ProductContentWorker contentWorker;
	@Autowired
	ProductContentService contentService;
	@Autowired
	ProductContentSnapshotRepository contentSnapshots;
	@Autowired
	ProductContentCollectionRepository contentCollections;
	@Autowired
	ProductContentReviewRepository contentReviews;
	@MockitoBean
	ProductContentSource contentSource;
	@Autowired
	ProductRepository products;
	@Autowired
	ProductSourceCollectionRepository collections;
	@Autowired
	ProductSourceSnapshotRepository snapshots;
	@Autowired
	ProductSourceReviewRepository reviews;
	@Autowired
	ProductContentLaneRepository lanes;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	ProductChangeTargetRepository targets;
	@Autowired
	ProductEditPlanner planner;
	@Autowired
	ObjectMapper mapper;
	@Autowired
	PlatformTransactionManager transactions;
	@MockitoSpyBean
	ProductChangeHistoryRepository histories;
	@MockitoBean
	ProductSourceObservationSource source;
	@MockitoBean
	VendorPricePolicyService vendorPolicies;
	@MockitoBean
	MarketSalePriceResolver prices;
	TransactionTemplate tx;

	static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	@BeforeEach
	void setup() {
		tx = new TransactionTemplate(transactions);
		reset(histories, source, contentSource, vendorPolicies, prices);
		contentReviews.deleteAll();
		contentSnapshots.deleteAll();
		contentCollections.deleteAll();
		targets.deleteAll();
		histories.deleteAll();
		reviews.deleteAll();
		snapshots.deleteAll();
		collections.deleteAll();
		lanes.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		lanes.saveAndFlush(new ProductContentLane("PRICE_STOCK"));
		lanes.saveAndFlush(new ProductContentLane("IHB"));
		for (String vendor : List.of("IHB", "VTB", "FTN", "COK", "OCD"))
			lanes.saveAndFlush(new ProductContentLane("SOURCE_" + vendor));
		when(vendorPolicies.find(VendorType.IHB))
			.thenReturn(Optional.of(VendorPricePolicy.builder().vendor(VendorType.IHB)
				.shipCurrency("KRW").shipBaseAmount(BigDecimal.ZERO).build()));
		when(source.fetch(any(), anyString()))
			.thenReturn(new Observed(money("12000"), BigDecimal.ONE, "KRW", StockStatus.OUT_OF_STOCK, null, List.of()));
		when(prices.explainForProduct(any(), any(), any())).thenReturn(new MarketSalePriceResolver.Explanation(
			MarketSalePriceResolver.Basis.CALCULATED, SalePriceRounding.fromPrice(money("18000"), money("15000"))));
	}

	Product product() {
		return tx.execute(s -> {
			Product p = Product.create("SB-" + UUID.randomUUID(),
				new ProductCreateCommand("https://kr.iherb.com/pr/example/12345",
					money("10000"), "기본 상품", "Original", "브랜드", "US", money("0.3"), money("25"), MeasureUnit.G,
					List.of(), List.of(), "상세", "FOOD", true, 3, money("20"), VendorType.IHB, null));
			p.update(ProductUpdateCommand.builder().salePrice(money("10000")).stock(77).build());
			return products.saveAndFlush(p);
		});
	}

	ProductSourceService.Collection collect(Product... p) {
		return service.collect(new ProductSourceService.CollectionRequest(
			UUID.randomUUID().toString(), Arrays.stream(p).map(Product::getId).toList()), "admin");
	}

	ProductSourceService.Snapshot ready(Product p) {
		var c = collect(p);
		worker.tick();
		return service.collection(c.id(), "admin").items().getFirst();
	}

	ProductEditService.Review review(ProductSourceService.Snapshot s, Field... fields) {
		return service.review(new ProductSourceService.ReviewRequest(
			List.of(new ProductSourceService.Selection(s.id(), List.of(fields)))), "admin");
	}

	void openLane() {
		tx.executeWithoutResult(s -> {
			for (String id : List.of("PRICE_STOCK", "IHB", "SOURCE_IHB"))
				ReflectionTestUtils.setField(lanes.findLocked(id).orElseThrow(), "nextAllowedAt",
					Instant.now().minusSeconds(1));
		});
	}

	@Test
	void batchReviewCannotBeCommittedByOrdinarySourceAndOrdinaryReviewCannotEnterBatch() {
		Product p = product();
		var snapshot = ready(p);
		var ordinary = review(snapshot, Field.STOCK);
		var batch = batchSource.review(snapshot.id(), Set.of(ProductSupplierBatchService.Field.STOCK), batchPolicy(),
			"admin");
		assertThatThrownBy(() -> service.commit(batch.reviewId(), "admin"))
			.isInstanceOf(ProductEditConflictException.class).hasMessageContaining("배치 실행 경로");
		assertThatThrownBy(() -> batchSource.commit(ordinary.reviewId(), "admin"))
			.isInstanceOf(ProductEditConflictException.class).hasMessageContaining("배치 전용");
		assertThat(products.findById(p.getId()).orElseThrow().getRevision()).isEqualTo(p.getRevision());
		assertThat(histories.count()).isZero();
		assertThat(targets.count()).isZero();
	}

	@Test
	void batchPolicyAndSelectedObservationsStoreOnceAndRetainFirstApplicationTimesAfterExpiry() {
		Product p = product();
		var snapshot = ready(p);
		var batch = batchSource.review(snapshot.id(), EnumSet.allOf(ProductSupplierBatchService.Field.class),
			batchPolicy(), "admin");
		var first = batchSource.commit(batch.reviewId(), "admin");
		var applied = snapshots.findById(snapshot.id()).orElseThrow();
		assertThat(first.state()).isEqualTo("SAVED");
		assertThat(applied.getPriceAppliedAt()).isNotNull();
		assertThat(applied.getStockAppliedAt()).isEqualTo(applied.getPriceAppliedAt());
		var after = products.findById(p.getId()).orElseThrow();
		assertThat(after.getPriceInfo().getMarginRate()).isEqualByComparingTo("10");
		assertThat(after.getPriceInfo().getCouponRate()).isEqualByComparingTo("20");
		assertThat(after.getPriceInfo().getMinMarginPrice()).isEqualByComparingTo("1500");
		assertThat(after.getPriceInfo().getCostPrice()).isEqualByComparingTo("12000");
		assertThat(after.getStock()).isEqualTo(77);
		assertThat(after.getSalesQuantity()).isEqualTo(300);
		tx.executeWithoutResult(s -> {
			ReflectionTestUtils.setField(snapshots.findLocked(snapshot.id()).orElseThrow(), "expiresAt",
				Instant.now().minusSeconds(1));
			ReflectionTestUtils.setField(reviews.findById(batch.reviewId()).orElseThrow(), "expiresAt",
				Instant.now().minusSeconds(1));
		});
		var repeated = batchSource.commit(batch.reviewId(), "admin");
		assertThat(repeated.historyId()).isEqualTo(first.historyId());
		assertThat(repeated.revision()).isEqualTo(first.revision());
		var repeatedSnapshot = snapshots.findById(snapshot.id()).orElseThrow();
		assertThat(repeatedSnapshot.getPriceAppliedAt()).isEqualTo(applied.getPriceAppliedAt());
		assertThat(repeatedSnapshot.getStockAppliedAt()).isEqualTo(applied.getStockAppliedAt());
		assertThat(histories.count()).isEqualTo(1);
		verify(source, times(1)).fetch(any(), anyString());
	}

	@Test
	void stockOnlyBatchAcceptsPartialCollectionWithoutChangingPricingPolicy() {
		Product p = product();
		when(source.fetch(any(), anyString()))
			.thenReturn(new Observed(null, null, "KRW", StockStatus.OUT_OF_STOCK, null, List.of("환율 확인 실패")));
		var snapshot = ready(p);
		assertThat(snapshot.state()).isEqualTo(ProductSourceSnapshot.State.PARTIAL);
		assertThatThrownBy(() -> batchSource.review(snapshot.id(), Set.of(ProductSupplierBatchService.Field.PRICE),
			batchPolicy(), "admin")).isInstanceOf(ProductEditConflictException.class);
		var batch = batchSource.review(snapshot.id(), Set.of(ProductSupplierBatchService.Field.STOCK), batchPolicy(),
			"admin");
		assertThat(batchSource.commit(batch.reviewId(), "admin").state()).isEqualTo("SAVED");
		var after = products.findById(p.getId()).orElseThrow();
		assertThat(after.getPriceInfo().getMarginRate()).isEqualByComparingTo(p.getPriceInfo().getMarginRate());
		assertThat(after.getPriceInfo().getCouponRate()).isEqualTo(p.getPriceInfo().getCouponRate());
		assertThat(after.getPriceInfo().getCostPrice()).isEqualByComparingTo("10000");
		assertThat(after.getSalePrice()).isEqualByComparingTo(p.getSalePrice());
		assertThat(after.getStock()).isEqualTo(77);
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getPriceAppliedAt()).isNull();
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getStockAppliedAt()).isNotNull();
	}

	@Test
	void batchDatabaseFailureReusesCollectedSnapshotAndRollsBackApplicationTime() {
		Product p = product();
		var snapshot = ready(p);
		var batch = batchSource.review(snapshot.id(), EnumSet.allOf(ProductSupplierBatchService.Field.class),
			batchPolicy(), "admin");
		doThrow(new IllegalStateException("history storage unavailable")).when(histories).save(any());
		assertThatThrownBy(() -> batchSource.commit(batch.reviewId(), "admin"))
			.isInstanceOf(IllegalStateException.class);
		assertThat(products.findById(p.getId()).orElseThrow().getRevision()).isEqualTo(p.getRevision());
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getPriceAppliedAt()).isNull();
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getStockAppliedAt()).isNull();
		reset(histories);
		assertThat(batchSource.commit(batch.reviewId(), "admin").state()).isEqualTo("SAVED");
		assertThat(histories.count()).isEqualTo(1);
		verify(source, times(1)).fetch(any(), anyString());
	}

	@Test
	void batchReviewAndCommitKeepActorUrlVendorShippingAndExpiryChecks() {
		for (String conflict : List.of("actor", "url", "vendor", "shipping", "expiry")) {
			openLane();
			Product p = product();
			var snapshot = ready(p);
			assertThatThrownBy(() -> batchSource.review(snapshot.id(), Set.of(ProductSupplierBatchService.Field.PRICE),
				batchPolicy(), "other")).isInstanceOf(ProductEditConflictException.class);
			var batch = batchSource.review(snapshot.id(), Set.of(ProductSupplierBatchService.Field.PRICE),
				batchPolicy(), "admin");
			if (conflict.equals("url"))
				tx.executeWithoutResult(s -> products.findById(p.getId()).orElseThrow()
					.update(ProductUpdateCommand.builder().sourceUrl("https://kr.iherb.com/pr/other/54321").build()));
			if (conflict.equals("vendor"))
				tx.executeWithoutResult(s -> products.findById(p.getId()).orElseThrow()
					.update(ProductUpdateCommand.builder().vendor(VendorType.VTB).build()));
			if (conflict.equals("shipping"))
				when(vendorPolicies.find(VendorType.IHB)).thenReturn(Optional.of(VendorPricePolicy.builder()
					.vendor(VendorType.IHB).shipCurrency("KRW").shipBaseAmount(money("6000")).build()));
			if (conflict.equals("expiry"))
				tx.executeWithoutResult(s -> ReflectionTestUtils.setField(reviews.findById(batch.reviewId()).orElseThrow(),
					"expiresAt", Instant.now().minusSeconds(1)));
			assertThatThrownBy(() -> batchSource.commit(batch.reviewId(), conflict.equals("actor") ? "other" : "admin"))
				.isInstanceOf(ProductEditConflictException.class);
			when(vendorPolicies.find(VendorType.IHB)).thenReturn(Optional.of(VendorPricePolicy.builder()
				.vendor(VendorType.IHB).shipCurrency("KRW").shipBaseAmount(BigDecimal.ZERO).build()));
		}
		assertThat(histories.count()).isZero();
	}

	private ProductSupplierBatchService.Policy batchPolicy() {
		return new ProductSupplierBatchService.Policy(money("10"), money("20"), money("1500"));
	}

	@Test
	void collectionIsDurableIdempotentAndActorBound() {
		Product p = product();
		var request = new ProductSourceService.CollectionRequest(UUID.randomUUID().toString(), List.of(p.getId()));
		var first = service.collect(request, "admin");
		assertThat(service.collect(request, "admin").id()).isEqualTo(first.id());
		assertThat(snapshots.count()).isEqualTo(1);
		assertThatThrownBy(() -> service.collection(first.id(), "other")).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> service
			.collect(new ProductSourceService.CollectionRequest(request.requestId(), List.of(999L)), "admin"))
			.isInstanceOf(ProductEditConflictException.class);
	}

	@Test
	void collectionAndRemoteCallNeverChangeProductAndSeparateSuccessFromAppliedTime() {
		Product p = product();
		doAnswer(i -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new Observed(money("12000"), BigDecimal.ONE, "KRW", StockStatus.OUT_OF_STOCK, null, List.of());
		}).when(source).fetch(any(), anyString());
		var s = ready(p);
		assertThat(s.state()).isEqualTo(ProductSourceSnapshot.State.READY);
		assertThat(s.collectedAt()).isNotNull();
		assertThat(s.appliedAt()).isNull();
		var unchanged = products.findById(p.getId()).orElseThrow();
		assertThat(unchanged.getRevision()).isEqualTo(p.getRevision());
		assertThat(unchanged.getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
		assertThat(histories.count()).isZero();
	}

	@Test
	void normalizedFxReviewAndSaveUseSameLandedCostAndRetainOriginalRateEvidence() throws Exception {
		Product p = product();
		tx.executeWithoutResult(s -> products.findById(p.getId()).orElseThrow().update(ProductUpdateCommand.builder()
			.vendor(VendorType.FTN)
			.sourceUrl("https://www.fortnumandmason.com/fortnum-s-fig-fennel-chutney-250g").build()));
		var shipping = VendorPricePolicy.builder().vendor(VendorType.FTN).shipCurrency("GBP")
			.shipBaseAmount(money("2.95")).shipBaseWeightG(1000).build();
		when(vendorPolicies.find(VendorType.FTN)).thenReturn(Optional.of(shipping));
		var evidence = new PricingEvidence(money("999.99"), "GBP", money("1822.551899"), money("1822.55"),
			money("1822532"));
		when(source.fetch(eq(VendorType.FTN), anyString())).thenReturn(new Observed(evidence.goodsPriceKrw(),
			evidence.normalizedExchangeRate(), "GBP", StockStatus.IN_STOCK, null,
			List.of("수집 원본 환율 1822.551899 → 1822.55 (소수 2자리 반올림)."), evidence));
		var collected = ready(products.findById(p.getId()).orElseThrow());
		var stored = mapper.readValue(snapshots.findById(collected.id()).orElseThrow().getProposed(), Proposed.class);
		assertThat(stored.pricingEvidence()).isEqualTo(evidence);
		BigDecimal expected = com.sbshop.agent.core.domain.pricing.LandedCostCalculator.buyPricePerUnit(
			evidence.goodsPriceKrw(), money("0.3"), 3, shipping, evidence.normalizedExchangeRate());
		assertThat(collected.proposed().costPrice()).isEqualByComparingTo(expected);
		assertThat(collected.proposed().exchangeRate()).isEqualByComparingTo("1822.55");
		assertThat(products.findById(p.getId()).orElseThrow().getPriceInfo().getCostPrice())
			.isEqualByComparingTo("10000");
		var review = review(collected, Field.PRICE);
		assertThat(review.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.READY);
		assertThat(review.items().getFirst().command().exchangeRate())
			.isEqualByComparingTo("1822.55");
		assertThat(service.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		var saved = products.findById(p.getId()).orElseThrow();
		assertThat(saved.getPriceInfo().getExchangeRate()).isEqualByComparingTo("1822.55");
		assertThat(saved.getPriceInfo().getCostPrice()).isEqualByComparingTo(expected);
		assertThat(saved.getSalesQuantity()).isEqualTo(300);
		assertThat(saved.getSalePrice().remainder(money("100"))).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(snapshots.findById(collected.id()).orElseThrow().getPriceAppliedAt()).isNotNull();
	}

	@Test
	void unnormalizedPortObservationCannotBeStoredAndOldSnapshotJsonRemainsReadable() throws Exception {
		Product p = product();
		when(source.fetch(any(), anyString())).thenReturn(new Observed(money("12000"), money("1822.551899"),
			"KRW", StockStatus.IN_STOCK, null, List.of()));
		var collected = ready(p);
		assertThat(collected.state()).isEqualTo(ProductSourceSnapshot.State.PARTIAL);
		assertThat(collected.proposed().costPrice()).isNull();
		assertThat(review(collected, Field.PRICE).items().getFirst().state())
			.isEqualTo(ProductEditPlanner.State.EXCLUDED);
		var old = mapper.readValue("{\"values\":{\"costPrice\":12000,\"exchangeRate\":1,\"stockStatus\":\"IN_STOCK\","
			+ "\"stock\":null},\"priceAvailable\":true,\"stockAvailable\":true,\"notices\":[]}", Proposed.class);
		assertThat(old.pricingEvidence()).isNull();
		assertThat(old.priceAvailable()).isTrue();
	}

	@Test
	void sourceStockSavePreservesSalesQuantityAndMissingActualQuantityAndIsIdempotent() {
		Product p = product();
		var s = ready(p);
		var r = review(s, Field.STOCK);
		assertThat(r.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.READY);
		var first = service.commit(r.reviewId(), "admin");
		var again = service.commit(r.reviewId(), "admin");
		assertThat(first.items().getFirst().state()).isEqualTo("SAVED");
		assertThat(again.items().getFirst().historyId()).isEqualTo(first.items().getFirst().historyId());
		var changed = products.findById(p.getId()).orElseThrow();
		assertThat(changed.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
		assertThat(changed.getStock()).isEqualTo(77);
		assertThat(changed.getSalesQuantity()).isEqualTo(300);
		assertThat(changed.getPriceInfo().getCostPrice()).isEqualByComparingTo("10000");
		assertThat(snapshots.findById(s.id()).orElseThrow().getStockAppliedAt()).isNotNull();
		assertThat(snapshots.findById(s.id()).orElseThrow().getPriceAppliedAt()).isNull();
		assertThat(histories.count()).isEqualTo(1);
	}

	@Test
	void regularEditorCannotForgeSourceStatusEvenWithNoMarketLinks() {
		Product p = product();
		var request = mapper.createObjectNode().put("stockStatus", "OUT_OF_STOCK");
		assertThat(planner.plan(p, request, List.of()).state()).isEqualTo(ProductEditPlanner.State.EXCLUDED);
		assertThat(planner.planSourceObservation(p, request, List.of()).state())
			.isEqualTo(ProductEditPlanner.State.READY);
		assertThatThrownBy(
			() -> planner.planSourceObservation(p, mapper.createObjectNode().put("salesQuantity", 999), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void missingShippingPolicyExcludesPriceButExplicitStockRemainsReviewable() {
		Product p = product();
		when(vendorPolicies.find(VendorType.IHB)).thenReturn(Optional.empty());
		var s = ready(p);
		assertThat(s.state()).isEqualTo(ProductSourceSnapshot.State.PARTIAL);
		assertThat(s.fields().getFirst().available()).isFalse();
		assertThat(s.fields().get(1).available()).isTrue();
		assertThat(review(s, Field.PRICE).items().getFirst().state()).isEqualTo(ProductEditPlanner.State.EXCLUDED);
		assertThat(service.commit(review(s, Field.STOCK).reviewId(), "admin").items().getFirst().state())
			.isEqualTo("SAVED");
	}

	@Test
	void sourceFailureNeverInventsZeroOrSuccessfulCollection() {
		Product p = product();
		when(source.fetch(any(), anyString())).thenThrow(ProductContentFailureException.http(404));
		var s = ready(p);
		assertThat(s.state()).isEqualTo(ProductSourceSnapshot.State.FAILED);
		assertThat(s.collectedAt()).isNull();
		assertThat(s.appliedAt()).isNull();
		assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(77);
		assertThat(products.findById(p.getId()).orElseThrow().getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
	}

	@Test
	void productOrSourceChangesBetweenReviewAndSaveBlockEverything() {
		Product p = product();
		var s = ready(p);
		var r = review(s, Field.PRICE, Field.STOCK);
		tx.executeWithoutResult(x -> products.findForEdit(p.getId()).orElseThrow().update(ProductUpdateCommand.builder()
			.sourceUrl("https://kr.iherb.com/pr/other/12346").build()));
		assertThat(service.commit(r.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
		assertThat(histories.count()).isZero();
		assertThat(snapshots.findById(s.id()).orElseThrow().getPriceAppliedAt()).isNull();
	}

	@Test
	void shippingPolicyChangesAfterReviewBlockPriceSave() {
		Product p = product();
		var r = review(ready(p), Field.PRICE);
		when(vendorPolicies.find(VendorType.IHB))
			.thenReturn(Optional.of(VendorPricePolicy.builder().vendor(VendorType.IHB)
				.shipCurrency("KRW").shipBaseAmount(money("1000")).build()));
		assertThat(service.commit(r.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
		assertThat(histories.count()).isZero();
	}

	@Test
	void historyFailureRollsBackProductAndAppliedTimeTogether() {
		Product p = product();
		var s = ready(p);
		var r = review(s, Field.STOCK);
		doThrow(new org.springframework.dao.DataIntegrityViolationException("test write failure")).when(histories)
			.save(any());
		assertThat(service.commit(r.reviewId(), "admin").items().getFirst().state()).isEqualTo("FAILED");
		assertThat(products.findById(p.getId()).orElseThrow().getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
		assertThat(snapshots.findById(s.id()).orElseThrow().getStockAppliedAt()).isNull();
	}

	@Test
	void source429HonorsServerCooldownAndDoesNotRunNextProduct() {
		Product p = product();
		Product q = product();
		Instant next = Instant.now().plusSeconds(900);
		when(source.fetch(any(), anyString())).thenThrow(new ProductContentThrottledException(next));
		collect(p, q);
		worker.tick();
		worker.tick();
		verify(source, times(1)).fetch(any(), anyString());
		assertThat(lanes.findById("SOURCE_IHB").orElseThrow().getNextAllowedAt()).isAfterOrEqualTo(next);
		assertThat(snapshots.findAll()).allSatisfy(s -> assertThat(s.getCollectedAt()).isNull());
	}

	@Test
	void ocadoPriceStock429AlsoDefersContentWithoutRecordingSuccessOrChangingInventory() {
		Product p = product();
		String url = "https://www.ocado.com/products/cirio-tomato-puree-80259011";
		tx.executeWithoutResult(s -> products.findForEdit(p.getId()).orElseThrow().update(
			ProductUpdateCommand.builder().vendor(VendorType.OCD).sourceUrl(url).build()));
		Product current = products.findById(p.getId()).orElseThrow();
		Instant next = Instant.now().plusSeconds(900);
		when(source.fetch(VendorType.OCD, url)).thenThrow(new ProductContentThrottledException(next));
		var collected = collect(current);
		worker.tick();
		var content = contentService.collect(new ProductContentService.CollectionRequest(
			UUID.randomUUID().toString(), List.of(p.getId())), "admin");
		contentWorker.tick();
		verify(source, times(1)).fetch(VendorType.OCD, url);
		verifyNoInteractions(contentSource);
		assertThat(service.collection(collected.id(), "admin").items().getFirst().collectedAt()).isNull();
		assertThat(contentService.collection(content.id(), "admin").items().getFirst().collectedAt()).isNull();
		assertThat(lanes.findById("SOURCE_OCD").orElseThrow().getNextAllowedAt()).isAfterOrEqualTo(next);
		assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(77);
		assertThat(products.findById(p.getId()).orElseThrow().getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
	}

	@Test
	void restartExpiresInflightWithoutInventingSuccessAndQueuedWorkSurvives() {
		Product p = product();
		var c = collect(p);
		var s = c.items().getFirst();
		tx.executeWithoutResult(x -> {
			snapshots.findLocked(s.id()).orElseThrow().claim("old-token");
			var lane = lanes.findLocked("PRICE_STOCK").orElseThrow();
			lane.claim(s.id(), Instant.now().minusSeconds(700));
		});
		worker.tick();
		assertThat(snapshots.findById(s.id()).orElseThrow().getState()).isEqualTo(ProductSourceSnapshot.State.FAILED);
		assertThat(snapshots.findById(s.id()).orElseThrow().getCollectedAt()).isNull();
		verifyNoInteractions(source);
	}

	@Test
	void successfulReviewedPriceUsesCommonHistoryAndRecordsPendingMarketTargets() {
		Product p = product();
		tx.executeWithoutResult(s -> registrations.saveAndFlush(MarketRegistration.builder().productId(p.getId())
			.sbProductId(p.getId()).marketType(MarketType.COUPANG).marketIdentifiers(
				"{\"sellerProductId\":\"123\",\"vendorItemId\":\"456\"}")
			.build()));
		var collected = ready(products.findById(p.getId()).orElseThrow());
		var r = review(collected, Field.PRICE);
		assertThat(r.items().getFirst().state()).isEqualTo(ProductEditPlanner.State.READY);
		assertThat(service.commit(r.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		assertThat(products.findById(p.getId()).orElseThrow().getPriceInfo().getCostPrice())
			.isEqualByComparingTo("12000");
		assertThat(histories.count()).isEqualTo(1);
		assertThat(targets.findAll()).isNotEmpty()
			.allSatisfy(t -> assertThat(t.getState()).isEqualTo("PENDING_DISPATCH"));
	}

	@Test
	void applyingOnlyPriceDoesNotEraseEarlierUnreviewedStockFailure() {
		Product p = product();
		tx.executeWithoutResult(s -> products.findForEdit(p.getId()).orElseThrow().recordCrawlFailure("이전 재고 수집 실패"));
		var current = products.findById(p.getId()).orElseThrow();
		var r = review(ready(current), Field.PRICE);
		assertThat(service.commit(r.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		assertThat(products.findById(p.getId()).orElseThrow().getLastCrawlError()).isEqualTo("이전 재고 수집 실패");
	}

	@Test
	void completedCommitRemainsIdempotentEvenAfterShippingPolicyChanges() {
		Product p = product();
		var r = review(ready(p), Field.PRICE);
		var first = service.commit(r.reviewId(), "admin");
		when(vendorPolicies.find(VendorType.IHB)).thenReturn(Optional.empty());
		assertThat(service.commit(r.reviewId(), "admin").items().getFirst().historyId())
			.isEqualTo(first.items().getFirst().historyId());
		assertThat(histories.count()).isEqualTo(1);
	}

	@Test
	void contentAndPriceStockCannotFetchTheSameVendorConcurrently() {
		Product p = product();
		var content = contentService.collect(
			new ProductContentService.CollectionRequest(UUID.randomUUID().toString(), List.of(p.getId())), "admin");
		collect(p);
		when(source.fetch(any(), anyString())).thenAnswer(call -> {
			contentWorker.tick();
			verifyNoInteractions(contentSource);
			assertThat(contentSnapshots.findById(content.items().getFirst().id()).orElseThrow().getState())
				.isEqualTo(ProductContentSnapshot.State.QUEUED);
			return new Observed(money("12000"), BigDecimal.ONE, "KRW", StockStatus.IN_STOCK, null, List.of());
		});
		worker.tick();
		verifyNoInteractions(contentSource);
		openLane();
		when(contentSource.fetch(anyString()))
			.thenReturn(new ProductContentSource.Fetch(List.of(), List.of(), "<p>new</p>", false, true, List.of()));
		contentWorker.tick();
		verify(contentSource).fetch(p.getSourcingInfo().getSourceUrl());
	}

	@Test
	void priceStock429BlocksContentForThatVendorButAnotherVendorCanProceed() {
		Product ihb = product();
		Product vtb = product();
		tx.executeWithoutResult(s -> products.findForEdit(vtb.getId()).orElseThrow()
			.update(ProductUpdateCommand.builder().vendor(VendorType.VTB)
				.sourceUrl("https://www.vitabiotics.com/products/wellkid-multi-vitamin-liquid").build()));
		var collection = contentService.collect(new ProductContentService.CollectionRequest(
			UUID.randomUUID().toString(), List.of(ihb.getId(), vtb.getId())), "admin");
		collect(ihb);
		Instant next = Instant.now().plusSeconds(900);
		when(source.fetch(any(), anyString())).thenThrow(new ProductContentThrottledException(next));
		worker.tick();
		when(contentSource.fetch(anyString()))
			.thenReturn(new ProductContentSource.Fetch(List.of(), List.of(), "<p>new</p>", false, true, List.of()));
		contentWorker.tick();
		verify(contentSource).fetch("https://www.vitabiotics.com/products/wellkid-multi-vitamin-liquid");
		verify(contentSource, never()).fetch(ihb.getSourcingInfo().getSourceUrl());
		assertThat(lanes.findById("SOURCE_IHB").orElseThrow().getNextAllowedAt()).isAfterOrEqualTo(next);
		assertThat(
			contentSnapshots.findById(collection.items().stream().filter(item -> item.productId().equals(ihb.getId()))
				.findFirst().orElseThrow().id()).orElseThrow().getState())
			.isEqualTo(ProductContentSnapshot.State.QUEUED);
	}

	@Test
	void late429RetainsNewPermitAndStopsItsNextHttpWithoutHoldingTransaction() {
		String old = UUID.randomUUID().toString(), newer = UUID.randomUUID().toString();
		Instant now = Instant.now(), next = now.plusSeconds(900);
		tx.executeWithoutResult(s -> {
			assertThat(ProductSourceVendorGate.claim(lanes, "IHB", old, now.minusSeconds(700))).isTrue();
			assertThat(ProductSourceVendorGate.claim(lanes, "IHB", newer, now)).isTrue();
		});
		tx.executeWithoutResult(
			s -> assertThat(ProductSourceVendorGate.finish(lanes, "IHB", old, Instant.now(), true, next)).isFalse());
		assertThat(lanes.findById("SOURCE_IHB").orElseThrow().getSnapshotId()).isEqualTo(newer);
		var http = mock(Runnable.class);
		assertThatThrownBy(() -> ProductSourceHttpGuard
			.scoped(() -> ProductSourceVendorGate.beforeHttp(lanes, transactions, "IHB", newer), () -> {
				http.run();
				return null;
			})).isInstanceOf(ProductContentThrottledException.class);
		verifyNoInteractions(http);
		ProductSourceHttpGuard.check(); // scope always clears, including an exception
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		tx.executeWithoutResult(s -> ProductSourceVendorGate.finish(lanes, "IHB", newer, Instant.now(), false, null));
		assertThat(lanes.findById("SOURCE_IHB").orElseThrow().getNextAllowedAt()).isAfterOrEqualTo(next);
	}

	@Test
	void missingVendorGateFailsClosedWithoutAnExternalCall() {
		lanes.deleteById("SOURCE_IHB");
		var c = collect(product());
		worker.tick();
		verifyNoInteractions(source);
		assertThat(snapshots.findById(c.items().getFirst().id()).orElseThrow().getState())
			.isEqualTo(ProductSourceSnapshot.State.QUEUED);
	}

}
