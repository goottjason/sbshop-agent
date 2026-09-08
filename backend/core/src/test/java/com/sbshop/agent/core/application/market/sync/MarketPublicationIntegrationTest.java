package com.sbshop.agent.core.application.market.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.core.domain.product.service.SalePriceRounding;
import java.math.BigDecimal;
import java.time.*;
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
import org.springframework.transaction.annotation.*;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:publication;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketPublicationIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketPublicationIntegrationTest {
	@org.springframework.test.context.DynamicPropertySource
	static void postgres(org.springframework.test.context.DynamicPropertyRegistry r) {
		String url = System.getenv("SBSHOP_PUBLICATION_TEST_POSTGRES_URL");
		if (url == null)
			return;
		if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sbshop_publication_check"))
			throw new IllegalArgumentException("Only isolated publication test database allowed");
		r.add("spring.datasource.url", () -> url);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.datasource.username", () -> "postgres");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@SpringBootApplication
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketPriceTaskRepository.class, MarketInspectionGateRepository.class,
		com.sbshop.agent.core.domain.market.publication.MarketPublicationTaskRepository.class})
	@Import({MarketPriceSyncService.class, MarketPublicationService.class})
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper();
		}
	}

	@Autowired
	MarketPriceSyncService priceService;
	@Autowired
	MarketPublicationService service;
	@Autowired
	com.sbshop.agent.core.domain.market.publication.MarketPublicationTaskRepository publicationTasks;
	@Autowired
	MarketConnectionEventRepository events;
	@Autowired
	MarketPriceTaskRepository tasks;
	@Autowired
	MarketPriceReviewRepository reviews;
	@Autowired
	MarketPriceAttemptRepository attempts;
	@Autowired
	MarketInspectionGateRepository gates;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	JdbcTemplate jdbc;
	@MockitoBean
	MarketClientRouter clients;
	@MockitoBean
	MarketSalePriceResolver prices;
	MarketClient client;
	Product product;
	MarketRegistration reg;
	static final MarketType MARKET = MarketType.SMART_STORE;

	@BeforeEach
	void setup() {
		publicationTasks.deleteAll();
		events.deleteAll();
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
		doCallRealMethod().when(client).preparePublication(any(), any(), any());
		doCallRealMethod().when(client).submitPreparedPublication(any(), any(), any(), any());
		doCallRealMethod().when(client).readPreparedPublication(any(), any(), any());
		when(prices.explainForProduct(any(), any(), any()))
			.thenReturn(new MarketSalePriceResolver.Explanation(MarketSalePriceResolver.Basis.CALCULATED,
				SalePriceRounding.fromPrice(new BigDecimal("12300"), new BigDecimal("10000"))));
		product = products.saveAndFlush(Product.create("SB-" + UUID.randomUUID(),
			new ProductCreateCommand("https://example.com/item", new BigDecimal("10000"), "상품", "original", "브랜드", "US",
				new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "html", "FOOD", true,
				1, new BigDecimal("20"), VendorType.IHB, null)));
		reg = registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId()).marketType(MARKET)
			.marketIdentifiers("{\"originProductNo\":\"123\"}").build());
	}

	MarketPublicationService.View preview(){
  when(client.preparePublication(any(),any())).thenReturn(new com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication("{\"frozen\":true}","account-A","상품","50001","식품 > 영양제",new BigDecimal("12300"),300,"https://example.com/image.jpg"));
  return service.prepare(List.of(new MarketPublicationService.Pair(product.getId(),MARKET)),"admin").prepared().getFirst();
 }

	MarketPublicationService.View queue() {
		var r = preview();
		return service.commit(r.id(), "admin");
	}

	void deleted() {
		jdbc.update("update sb_market_registration set connection_state='DETACHED_DELETED' where id=?", reg.getId());
	}

	void observed(int price){when(client.readSalePrice("123",null)).thenReturn(new MarketPriceRead(BigDecimal.valueOf(price),true,"fixture","account-A"));}

	void release() {
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
		jdbc.update("update sb_market_publication_task set next_run_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
	}

	@Test
	void candidatesIncludeDeletedButExcludePermanentBanAndUnconfirmedExistingRequest() {
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().selectable()).isFalse();
		deleted();
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().selectable()).isTrue();
		jdbc.update("update sb_market_registration set connection_state='DETACHED_PROHIBITED' where id=?", reg.getId());
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().reason())
			.contains("영구 판매금지");
		registrations.deleteAll();
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().selectable()).isTrue();
	}

	@Test
	void confirmedRegistrationUsesFrozenPayloadAndArchivesOldIdWithoutWholeProductSuccess() {
		deleted();
		var r = queue();
		when(client.submitPreparedPublication(any(), eq(r.id()), eq("{\"frozen\":true}")))
			.thenReturn(Map.of("originProductNo", "456"));
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("VERIFY");
		when(client.verifyPreparedPublication(eq("456"), eq(product.getSbCode()), eq("{\"frozen\":true}")))
			.thenReturn(true);
		release();
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("REGISTERED");
		var linked = registrations.findById(reg.getId()).orElseThrow();
		assertThat(linked.extractLiveLookupId()).isEqualTo("456");
		assertThat(linked.getMarketIdentifiers()).contains("123", "previousIdentifiers", "DETACHED_DELETED");
		assertThat(linked.getPublicationOperationId()).isNull();
		assertThat(linked.getIsSynced()).isFalse();
		assertThat(events.count()).isEqualTo(1);
	}

	@Test
	void timeoutNeverRepeatsCreationAndOperatorIdOnlyTriggersReadback() {
		deleted();
		var r = queue();
		when(client.submitPreparedPublication(any(), any(), any()))
			.thenThrow(new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null));
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
		release();
		service.processOne();
		verify(client, times(1)).submitPreparedPublication(any(), any(), any());
		assertThat(registrations.findById(reg.getId()).orElseThrow().getPublicationOperationId()).isEqualTo(r.id());
		assertThatThrownBy(() -> service.recheck(r.id(), "123", "admin")).isInstanceOf(IllegalArgumentException.class);
		service.recheck(r.id(), "456", "admin");
		when(client.verifyPreparedPublication(any(), any(), any())).thenReturn(true);
		release();
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("REGISTERED");
		verify(client, times(1)).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void crashedPostIntentDoesNotRepostAndStaleLeaseCannotClaimSuccess() {
		deleted();
		var r = queue();
		var old = service.claim();
		assertThat(old.post()).isTrue();
		release();
		assertThat(service.claim()).isNull();
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
		service.finish(old, "REGISTERED", "late", Map.of("originProductNo", "456"), null);
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
		verify(client, never()).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void pendingIntentLocksIdentityAndLegacyPublishUntilVerified() {
		deleted();
		var r = queue();
		var row = registrations.findById(reg.getId()).orElseThrow();
		assertThat(new com.sbshop.agent.core.application.product.edit.ProductEditPolicy().rule("name", List.of(row))
			.editable()).isFalse();
		var guard = new com.sbshop.agent.core.application.market.MarketConnectionWriteGuard(registrations);
		assertThatThrownBy(() -> guard.requireWritable(MARKET, new Object[] {product}))
			.isInstanceOf(IllegalStateException.class);
		assertThatCode(() -> guard.requirePublicationIntent(MARKET, product, r.id())).doesNotThrowAnyException();
	}

	@Test
	void reviewIsActorBoundIdempotentAndRejectsChangedProduct() {
		deleted();
		var r = preview();
		assertThatThrownBy(() -> service.commit(r.id(), "other")).isInstanceOf(RuntimeException.class);
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		assertThatThrownBy(() -> service.commit(r.id(), "admin")).isInstanceOf(RuntimeException.class);
		var fresh = queue();
		assertThat(service.commit(fresh.id(), "admin").state()).isEqualTo("QUEUED");
	}

	@Test
	void changedUnsentProductReleasesIntentButAnUncertainPostDoesNot() {
		deleted();
		var r = queue();
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("STALE");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getPublicationOperationId()).isNull();
		verify(client, never()).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void prohibitedWhileVerifyingNeverReconnects() {
		deleted();
		var r = queue();
		when(client.submitPreparedPublication(any(), any(), any())).thenReturn(Map.of("originProductNo", "456"));
		service.processOne();
		jdbc.update("update sb_market_registration set connection_state='DETACHED_PROHIBITED' where id=?", reg.getId());
		release();
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.DETACHED_PROHIBITED);
	}

	MarketPublicationService.View queueFor(MarketType market) {
        when(clients.hasClient(market)).thenReturn(true);when(clients.getClient(market)).thenReturn(client);
        when(client.preparePublication(any(),any())).thenReturn(new com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication("{\"frozen\":true}","account-A","상품","50001","분류",new BigDecimal("12300"),300,"https://example.com/image.jpg",Map.of("반품비","3,000원")));
        var p=service.prepare(List.of(new MarketPublicationService.Pair(product.getId(),market)),"admin").prepared().getFirst();return service.commit(p.id(),"admin");
    }

	@Test
	void coupangReceiptAndApprovalPendingNeverConfirmOrRepeatCreation() {
		var r = queueFor(MarketType.COUPANG);
		when(client.submitPreparedPublication(any(), any(), any())).thenReturn(Map.of("sellerProductId", "789"));
		service.processOne(MarketType.COUPANG);
		assertThat(service.get(r.id()).state()).isEqualTo("VERIFY");
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(false,
			Map.of("sellerProductId", "789"), "심사중", true)).when(client).readPreparedPublication(any(), any(), any());
		for (int i = 0; i < 10; i++) {
			release();
			service.processOne(MarketType.COUPANG);
			assertThat(service.get(r.id()).state()).isEqualTo("AWAITING_APPROVAL");
		}
		var linked = registrations.findByProductIdAndMarketType(product.getId(), MarketType.COUPANG).orElseThrow();
		assertThat(linked.getPublicationOperationId()).isEqualTo(r.id());
		assertThat(linked.extractLiveLookupId()).isNull();
		verify(client, times(1)).submitPreparedPublication(any(), any(), any());
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(true,
			Map.of("sellerProductId", "789", "vendorItemId", "456"), "승인 완료 + 필드 조회 일치", false)).when(client)
			.readPreparedPublication(any(), any(), any());
		release();
		service.processOne(MarketType.COUPANG);
		linked = registrations.findById(linked.getId()).orElseThrow();
		assertThat(linked.identifier("vendorItemId")).isEqualTo("456");
		assertThat(linked.getPublicationOperationId()).isNull();
		assertThat(service.get(r.id()).state()).isEqualTo("REGISTERED");
		assertThat(service.get(r.id()).shippingSummary()).containsEntry("반품비", "3,000원");
	}

	@Test
	void missingCoupangOptionIdOrMismatchedListingNeverConnects() {
		var r = queueFor(MarketType.COUPANG);
		when(client.submitPreparedPublication(any(), any(), any())).thenReturn(Map.of("sellerProductId", "789"));
		service.processOne(MarketType.COUPANG);
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(true,
			Map.of("sellerProductId", "789"), "no option", false)).when(client)
			.readPreparedPublication(any(), any(), any());
		release();
		service.processOne(MarketType.COUPANG);
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		assertThat(registrations.findByProductIdAndMarketType(product.getId(), MarketType.COUPANG).orElseThrow()
			.getPublicationOperationId()).isEqualTo(r.id());
	}

	@Test
	void cafe24UsesNativeIdsAndDoesNotCreateMarketPlusChildren() {
		var r = queueFor(MarketType.CAFE24);
		when(client.submitPreparedPublication(any(), any(), any()))
			.thenReturn(Map.of("product_no", "789", "product_code", "P000000A"));
		service.processOne(MarketType.CAFE24);
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(true,
			Map.of("product_no", "789", "product_code", "P000000A", "variant_code", "P000000A000A"), "본상품 필드 일치",
			false)).when(client).readPreparedPublication(any(), any(), any());
		release();
		service.processOne(MarketType.CAFE24);
		assertThat(service.get(r.id()).state()).isEqualTo("REGISTERED");
		var row = registrations.findByProductIdAndMarketType(product.getId(), MarketType.CAFE24).orElseThrow();
		assertThat(row.identifier("variant_code")).isEqualTo("P000000A000A");
		assertThat(row.connectionIdentifier(MarketType.GMARKET)).isNull();
		assertThat(row.connectionIdentifier(MarketType.AUCTION)).isNull();
	}

	@Test
	void duplicatePostCallbackHasExactlyOneDurableAuthorization() {
		deleted();
		var r = queue();
		var c = service.claim();
		assertThat(service.beginPost(c)).isTrue();
		assertThat(service.beginPost(c)).isFalse();
		assertThat(publicationTasks.findById(r.id()).orElseThrow().isPostAuthorized()).isTrue();
		release();
		assertThat(service.claim()).isNull();
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
		verify(client, never()).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void revisionChangeBetweenClaimAndPostStopsCreationAndReleasesUnsentIntent() {
		deleted();
		var r = queue();
		var c = service.claim();
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		assertThat(service.beginPost(c)).isFalse();
		assertThat(service.get(r.id()).state()).isEqualTo("STALE");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getPublicationOperationId()).isNull();
		assertThat(publicationTasks.findById(r.id()).orElseThrow().isPostAuthorized()).isFalse();
	}

	@Test
	void networkCreateRunsOutsideTransactionAfterDurableGuard() {
		deleted();
		var r = queue();
		when(client.submitPreparedPublication(any(), any(), any())).thenAnswer(c -> {
			assertThat(
				org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
				.isFalse();
			assertThat(publicationTasks.findById(r.id()).orElseThrow().isPostAuthorized()).isTrue();
			return Map.of("originProductNo", "456");
		});
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("VERIFY");
	}

	@Test
	void uncertainCoupangPostRecheckOnlyReadsSuppliedSellerId() {
		var r = queueFor(MarketType.COUPANG);
		when(client.submitPreparedPublication(any(), any(), any()))
			.thenThrow(new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null));
		service.processOne(MarketType.COUPANG);
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
		service.recheck(r.id(), "789", "admin");
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(true,
			Map.of("sellerProductId", "789", "vendorItemId", "456"), "정확한 상품 일치", false)).when(client)
			.readPreparedPublication(any(), any(), any());
		release();
		service.processOne(MarketType.COUPANG);
		assertThat(service.get(r.id()).state()).isEqualTo("REGISTERED");
		verify(client, times(1)).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void sourceUnknownOrCrawlErrorCannotBecomeNewRegistrationCandidate() {
		deleted();
		jdbc.update("update sb_product set stock_status=null where id=?", product.getId());
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().selectable()).isFalse();
		jdbc.update("update sb_product set stock_status='IN_STOCK',last_crawl_error='timeout' where id=?",
			product.getId());
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().selectable()).isFalse();
	}

	@Test
	void stalePublication429ExtendsCooldownWithoutStealingAnotherLease() {
		deleted();
		var r = queue();
		var old = service.claim();
		release();
		assertThat(service.claim()).isNull();
		var gate = gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow();
		gate.claim("other-worker", Instant.now().plusSeconds(180));
		gates.saveAndFlush(gate);
		Instant retry = Instant.now().plusSeconds(500);
		service.finish(old, "UNKNOWN_CREATE", "late429", null,
			new MarketTransferFailure("HTTP_429", "limited", retry, null));
		var after = gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow();
		assertThat(after.getLeaseToken()).isEqualTo("other-worker");
		assertThat(after.getNextAllowedAt()).isAfterOrEqualTo(retry);
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
	}

	MarketPublicationService.View createdCafe24() {
		var r = queueFor(MarketType.CAFE24);
		when(client.submitPreparedPublication(any(), any(), any()))
			.thenReturn(Map.of("product_no", "789", "product_code", "P000000A"));
		service.processOne(MarketType.CAFE24);
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(false,
			Map.of("product_no", "789", "product_code", "P000000A", "variant_code", "P000000A000A"), "새 상품 설정 필요",
			false, true)).when(client).readPreparedPublication(any(), any(), any());
		return r;
	}

	@Test
	void cafeSetupRequiresReceiptAndDurableWriteIntentOutsideTransactionThenSeparateRead() {
		var r = createdCafe24();
		doAnswer(call -> {
			assertThat(
				org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
				.isFalse();
			((Runnable)call.getArgument(3)).run();
			assertThat(publicationTasks.findById(r.id()).orElseThrow().getSetupWrites()).isEqualTo(1);
			return null;
		}).when(client).finalizePreparedPublication(any(), any(), any(), any());
		release();
		service.processOne(MarketType.CAFE24);
		assertThat(service.get(r.id()).state()).isEqualTo("VERIFY");
		assertThat(events.count()).isZero();
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(true,
			Map.of("product_no", "789", "product_code", "P000000A", "variant_code", "P000000A000A"), "조회 일치", false))
			.when(client).readPreparedPublication(any(), any(), any());
		release();
		service.processOne(MarketType.CAFE24);
		assertThat(service.get(r.id()).state()).isEqualTo("REGISTERED");
		assertThat(registrations.findByProductIdAndMarketType(product.getId(), MarketType.CAFE24).orElseThrow()
			.getMarketIdentifiers()).doesNotContain("_sbshop_receipt_operation");
		verify(client, times(1)).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void uncertainCafeCreationManualIdentifierNeverAuthorizesSetup() {
		var r = queueFor(MarketType.CAFE24);
		when(client.submitPreparedPublication(any(), any(), any()))
			.thenThrow(new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null));
		service.processOne(MarketType.CAFE24);
		service.recheck(r.id(), "789", "admin");
		release();
		var c = service.claim(MarketType.CAFE24);
		assertThat(service.beginSetup(c)).isFalse();
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		assertThat(publicationTasks.findById(r.id()).orElseThrow().getSetupWrites()).isZero();
		verify(client, times(1)).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void newReceiptSetupStopsAtThreePhysicalWritesAndManualSameIdRetryKeepsReceipt() {
		var r = createdCafe24();
		for (int i = 0; i < 3; i++) {
			release();
			var c = service.claim(MarketType.CAFE24);
			assertThat(service.beginSetup(c)).isTrue();
			service.finish(c, "VERIFY", "read again", null, null);
		}
		release();
		var c = service.claim(MarketType.CAFE24);
		assertThat(service.beginSetup(c)).isFalse();
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		assertThat(publicationTasks.findById(r.id()).orElseThrow().getSetupWrites()).isEqualTo(3);
		service.recheck(r.id(), "789", "admin");
		release();
		c = service.claim(MarketType.CAFE24);
		assertThat(service.beginSetup(c)).isTrue();
		assertThat(publicationTasks.findById(r.id()).orElseThrow().getSetupWrites()).isEqualTo(1);
	}

	@Test
	void manuallyChangingKnownReceiptIdCannotMoveSetupAuthorizationToAnotherProduct() {
		var r = createdCafe24();
		release();
		var c = service.claim(MarketType.CAFE24);
		service.finish(c, "ACTION_REQUIRED", "manual check", null, null);
		service.recheck(r.id(), "790", "admin");
		release();
		assertThat(service.beginSetup(service.claim(MarketType.CAFE24))).isFalse();
		assertThat(publicationTasks.findById(r.id()).orElseThrow().getReturnedIdentifiers())
			.doesNotContain("_sbshop_receipt_operation");
	}

	@Test
	void sourceOrRevisionChangedAfterReadNeverStartsNewProductSelling() {
		var r = createdCafe24();
		release();
		var c = service.claim(MarketType.CAFE24);
		jdbc.update("update sb_product set last_crawl_error='timeout' where id=?", product.getId());
		assertThat(service.beginSetup(c)).isFalse();
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		assertThat(publicationTasks.findById(r.id()).orElseThrow().getSetupWrites()).isZero();
	}

	@Test
	void late429BeforeSetupPreservesCooldownAndBlocksPutWithReceipt() {
		var r = createdCafe24();
		release();
		var old = service.claim(MarketType.CAFE24);
		release();
		var current = service.claim(MarketType.CAFE24);
		Instant retry = Instant.now().plusSeconds(300);
		service.finish(old, "VERIFY", "late", null, new MarketTransferFailure("HTTP_429", "limited", retry, null));
		assertThat(gates.findById("CAFE24_ORIGIN_READ").orElseThrow().getLeaseToken()).isEqualTo(current.token());
		assertThat(service.beginSetup(current)).isFalse();
		assertThat(publicationTasks.findById(r.id()).orElseThrow().getSetupWrites()).isZero();
		assertThat(gates.findById("CAFE24_ORIGIN_READ").orElseThrow().getNextAllowedAt()).isAfterOrEqualTo(retry);
	}

	@Test
	void explicitSetupRejectionStopsRewritesWhileTimeoutFirstReadsAgain() {
		var r = createdCafe24();
		doAnswer(call -> {
			((Runnable)call.getArgument(3)).run();
			throw new MarketTransferFailure("HTTP_422", "invalid quantity", null, null);
		}).when(client).finalizePreparedPublication(any(), any(), any(), any());
		release();
		service.processOne(MarketType.CAFE24);
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		release();
		service.processOne(MarketType.CAFE24);
		verify(client, times(1)).finalizePreparedPublication(any(), any(), any(), any());
		service.recheck(r.id(), "789", "admin");
		doAnswer(call -> {
			((Runnable)call.getArgument(3)).run();
			throw new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null);
		}).when(client).finalizePreparedPublication(any(), any(), any(), any());
		release();
		service.processOne(MarketType.CAFE24);
		assertThat(service.get(r.id()).state()).isEqualTo("VERIFY");
	}

	@Test
	void setupProofMustIdentifySameCreatedProductAndExactVariant() {
		var r = createdCafe24();
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(false,
			Map.of("product_no", "790", "product_code", "P000000A", "variant_code", "P000000A000A"), "wrong listing",
			false, true)).when(client).readPreparedPublication(any(), any(), any());
		release();
		service.processOne(MarketType.CAFE24);
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		verify(client, never()).finalizePreparedPublication(any(), any(), any(), any());
	}

	@Test
	void nativeReadWithoutExactVariantCannotFinishRegistration() {
		var r = createdCafe24();
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(true,
			Map.of("product_no", "789", "product_code", "P000000A"), "missing variant", false)).when(client)
			.readPreparedPublication(any(), any(), any());
		release();
		service.processOne(MarketType.CAFE24);
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		assertThat(events.count()).isZero();
	}

	@Test
	void connectionAndEventRemainAtomicIfHistoryInsertFails() {
		var r = createdCafe24();
		doReturn(new com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication(true,
			Map.of("product_no", "789", "product_code", "P000000A", "variant_code", "P000000A000A"), "matches", false))
			.when(client).readPreparedPublication(any(), any(), any());
		jdbc.execute(
			"alter table sb_market_connection_event add constraint publication_test_no_success check (result <> 'REGISTERED')");
		try {
			release();
			service.processOne(MarketType.CAFE24);
			assertThat(service.get(r.id()).state()).isEqualTo("VERIFY");
			var row = registrations.findByProductIdAndMarketType(product.getId(), MarketType.CAFE24).orElseThrow();
			assertThat(row.getPublicationOperationId()).isEqualTo(r.id());
			assertThat(row.extractLiveLookupId()).isNull();
			assertThat(events.count()).isZero();
		} finally {
			jdbc.execute("alter table sb_market_connection_event drop constraint publication_test_no_success");
		}
	}

	@Test
	void previewHonorsExistingSharedCooldownBeforeCallingAdapter() {
		deleted();
		gates.saveAndFlush(new MarketInspectionGate("SMART_STORE_ORIGIN_READ", Instant.now().plusSeconds(300)));
		var r = service.prepare(List.of(new MarketPublicationService.Pair(product.getId(), MARKET)), "admin");
		assertThat(r.prepared()).isEmpty();
		assertThat(r.excluded().getFirst().reason()).contains("공용 호출 제한");
		verify(client, never()).preparePublication(any(), any(), any());
	}

	@Test
	void previewFenceStopsNextRequestOnLate429AndFinallyKeepsCooldown() {
		deleted();
		when(client.preparePublication(any(), any())).thenAnswer(call -> {
			assertThat(
				org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
				.isFalse();
			MarketPreparationRequestScope.beforeRequest(MARKET);
			MarketPreparationRequestScope.observedRateLimit(MARKET, Instant.now().plusSeconds(300));
			MarketPreparationRequestScope.beforeRequest(MARKET);
			throw new AssertionError("second request escaped");
		});
		var r = service.prepare(List.of(new MarketPublicationService.Pair(product.getId(), MARKET)), "admin");
		assertThat(r.prepared()).isEmpty();
		assertThat(r.excluded().getFirst().reason()).contains("다음 API 요청");
		var gate = gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow();
		assertThat(gate.getLeaseToken()).isNull();
		assertThat(gate.getNextAllowedAt()).isAfter(Instant.now().plusSeconds(290));
		assertThatCode(() -> MarketPreparationRequestScope.beforeRequest(MarketType.CAFE24)).doesNotThrowAnyException();
	}

	@Test
	void swallowedPreparation429StillCannotStoreAReadyReview() {
		deleted();
		when(client.preparePublication(any(), any())).thenAnswer(call -> {
			MarketPreparationRequestScope.observedRateLimit(MARKET, Instant.now().plusSeconds(300));
			return new com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication("{}", "account-A", "상품",
				"50001", null, new BigDecimal("12300"), 300, "https://example.com/a.png");
		});
		assertThat(
			service.prepare(List.of(new MarketPublicationService.Pair(product.getId(), MARKET)), "admin").prepared())
			.isEmpty();
		assertThat(publicationTasks.count()).isZero();
		assertThat(gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow().getLeaseToken()).isNull();
	}

	@Test
	void previewLostLeaseAndLate429NeverReleaseAnotherWorker() {
		deleted();
		when(client.preparePublication(any(), any())).thenAnswer(call -> {
			jdbc.update(
				"update sb_market_inspection_gate set lease_token='new-worker',lease_until=? where id='SMART_STORE_ORIGIN_READ'",
				java.sql.Timestamp.from(Instant.now().plusSeconds(180)));
			MarketPreparationRequestScope.observedRateLimit(MARKET, Instant.now().plusSeconds(400));
			MarketPreparationRequestScope.beforeRequest(MARKET);
			throw new AssertionError("escaped");
		});
		var r = service.prepare(List.of(new MarketPublicationService.Pair(product.getId(), MARKET)), "admin");
		assertThat(r.prepared()).isEmpty();
		var gate = gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow();
		assertThat(gate.getLeaseToken()).isEqualTo("new-worker");
		assertThat(gate.getNextAllowedAt()).isAfter(Instant.now().plusSeconds(390));
	}

	@Test
	void previewAccountRotationBlocksNextPhysicalRequestAndReleasesOwnLease() {
		deleted();
		when(client.preparePublication(any(), any())).thenAnswer(call -> {
			when(client.inspectionAccountReference()).thenReturn("other-account");
			MarketPreparationRequestScope.beforeRequest(MARKET);
			throw new AssertionError("escaped");
		});
		var r = service.prepare(List.of(new MarketPublicationService.Pair(product.getId(), MARKET)), "admin");
		assertThat(r.prepared()).isEmpty();
		assertThat(r.excluded().getFirst().reason()).contains("계정");
		assertThat(gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow().getLeaseToken()).isNull();
	}

	@Test
	void successfulPreviewsReleaseLeaseWithoutBlockingFollowingBatchMember() {
		deleted();
		var first = preview();
		var second = preview();
		assertThat(first.id()).isNotEqualTo(second.id());
		assertThat(gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow().getLeaseToken()).isNull();
		assertThatCode(() -> MarketPreparationRequestScope.beforeRequest(MarketType.CAFE24)).doesNotThrowAnyException();
	}
	@Test
	void readOnlyInputScopeUsesSharedGateWithoutProductTasksAndReleasesOnSuccess() {
		var result = service.withPreparationReadScope(MARKET, () -> {
			assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(MarketPreparationRequestScope.active()).isTrue();
			assertThat(gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow().getLeaseToken()).isNotNull();
			return "metadata";
		});
		assertThat(result).isEqualTo("metadata");
		assertThat(gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow().getLeaseToken()).isNull();
		assertThat(publicationTasks.count()).isZero();
		assertThat(MarketPreparationRequestScope.active()).isFalse();
	}

	@Test
	void readOnlyInputScopePreservesLate429AndNeverExposesPartialSuccess() {
		Instant retry = Instant.now().plusSeconds(600);
		assertThatThrownBy(() -> service.withPreparationReadScope(MARKET, () -> {
			MarketPreparationRequestScope.observedRateLimit(MARKET, retry);
			return "partial-addresses";
		})).isInstanceOf(MarketPreparationRequestScope.Blocked.class);
		var gate = gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow();
		assertThat(gate.getNextAllowedAt()).isAfterOrEqualTo(retry);
		assertThat(gate.getLeaseToken()).isNull();
		assertThat(MarketPreparationRequestScope.active()).isFalse();
		assertThatThrownBy(() -> service.withPreparationReadScope(MARKET, () -> "should not call"))
			.isInstanceOf(MarketPreparationRequestScope.Blocked.class);
		assertThat(publicationTasks.count()).isZero();
	}

	@Test
	void readOnlyInputScopeRetainsNewOwnersLeaseAndStopsOnAccountSwitch() {
		assertThatThrownBy(() -> service.withPreparationReadScope(MARKET, () -> {
			jdbc.update("update sb_market_inspection_gate set lease_token='new-owner',lease_until=? where id='SMART_STORE_ORIGIN_READ'", Instant.now().plusSeconds(300));
			return "stale-owner";
		})).isInstanceOf(MarketPreparationRequestScope.Blocked.class);
		assertThat(gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow().getLeaseToken()).isEqualTo("new-owner");
		gates.deleteAll();
		assertThatThrownBy(() -> service.withPreparationReadScope(MARKET, () -> {
			when(client.inspectionAccountReference()).thenReturn("account-B");
			return "wrong-account";
		})).hasMessageContaining("계정이 변경");
		assertThat(gates.findById("SMART_STORE_ORIGIN_READ").orElseThrow().getLeaseToken()).isNull();
		assertThat(publicationTasks.count()).isZero();
	}

}
