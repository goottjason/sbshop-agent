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
	@Autowired
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
