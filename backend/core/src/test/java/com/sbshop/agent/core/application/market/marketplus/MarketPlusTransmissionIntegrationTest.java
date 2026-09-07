package com.sbshop.agent.core.application.market.marketplus;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.*;
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
import org.springframework.transaction.annotation.*;

@DataJpaTest(showSql = false, properties = {
	"spring.datasource.url=jdbc:h2:mem:mptransmissions;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false", "marketplus.gmarket-account=seller-g", "marketplus.auction-account=seller-a"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketPlusTransmissionIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketPlusTransmissionIntegrationTest {
	@org.springframework.test.context.DynamicPropertySource
	static void isolatedPostgres(org.springframework.test.context.DynamicPropertyRegistry registry) {
		String url = System.getenv("SBSHOP_MARKETPLUS_TEST_POSTGRES_URL");
		if (url == null)
			return;
		if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sbshop_marketplus_check"))
			throw new IllegalArgumentException("Only the local isolated MarketPlus test database is allowed");
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@SpringBootApplication
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketPlusTransmissionRepository.class})
	@Import(MarketPlusTransmissionService.class)
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper();
		}
	}

	@Autowired
	MarketPlusTransmissionService service;
	@Autowired
	MarketPlusTransmissionRepository events;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	MarketCredentialRepository credentials;
	@Autowired
	ProductRepository products;
	@Autowired
	JdbcTemplate jdbc;
	Product product;
	MarketRegistration registration;
	static final Instant COMPLETED = Instant.parse("2026-09-06T07:02:00Z");

	@BeforeEach
	void setup() {
		if (System.getenv("SBSHOP_MARKETPLUS_TEST_POSTGRES_URL") == null) {
			jdbc.execute(
				"CREATE ALIAS IF NOT EXISTS sb_market_identifier_equals FOR 'com.sbshop.agent.core.domain.product.MarketSearchSqlFunctions.identifierEquals'");
			jdbc.execute(
				"CREATE ALIAS IF NOT EXISTS sb_market_has_identifier FOR 'com.sbshop.agent.core.domain.product.MarketSearchSqlFunctions.hasIdentifier'");
		}
		events.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		credentials.deleteAll();
		credentials.saveAndFlush(MarketCredential.builder().marketType(MarketType.CAFE24).clientId("testmall").build());
		product = products.saveAndFlush(Product.create("SB-" + UUID.randomUUID(),
			new ProductCreateCommand("https://example.com/item", new BigDecimal("10000"), "상품", "original", "브랜드", "US",
				new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "html", "FOOD", true,
				1, new BigDecimal("20"), VendorType.IHB, null)));
		registration = registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId())
			.marketType(MarketType.CAFE24)
			.marketIdentifiers(
				"{\"product_no\":\"10186\",\"product_code\":\"P0000PBU\",\"gmarket_goodsNo\":\"3490115053\",\"auction_goodsNo\":\"D888859044\"}")
			.build());
	}

	MarketPlusTransmissionService.Row row(String account, String productCode, String externalId, String outcome,
		String detail) {
		return new MarketPlusTransmissionService.Row(MarketType.GMARKET, account, "10186", productCode, externalId,
			"상품수정", outcome, detail, COMPLETED, COMPLETED);
	}

	MarketPlusTransmissionService.Row success() {
		return row("seller-g", "P0000PBU", "3490115053", "SUCCESS", "[성공] 전송이 완료되었습니다.");
	}

	MarketPlusTransmissionService.Batch batch(List<MarketPlusTransmissionService.Row> rows, Instant capture) {
		return new MarketPlusTransmissionService.Batch(1, "LIVE_CHROME_MARKETPLUS", "CURRENT_PAGE", "testmall", 1,
			capture, rows);
	}

	MarketPlusTransmissionService.ImportResult ingest(MarketPlusTransmissionService.Row... rows) {
		return service.ingest(batch(List.of(rows), COMPLETED.plusSeconds(60)), "admin");
	}

	@Test
	void observedSuccessNeverMarksSyncedAndRecaptureIsIdempotent() {
		var before = registrations.findById(registration.getId()).orElseThrow();
		assertThat(ingest(success()).saved()).isEqualTo(1);
		assertThat(service.ingest(batch(List.of(success()), COMPLETED.plusSeconds(120)), "admin").duplicate())
			.isEqualTo(1);
		var after = registrations.findById(registration.getId()).orElseThrow();
		assertThat(after.getIsSynced()).isEqualTo(before.getIsSynced());
		assertThat(after.getRevision()).isEqualTo(before.getRevision());
		assertThat(after.getMarketIdentifiers()).isEqualTo(before.getMarketIdentifiers());
		assertThat(events.count()).isEqualTo(1);
		assertThat(service.history(product.getId()).getFirst().observation().getReasonCode())
			.isEqualTo("TRANSFERRED_UNVERIFIED");
	}

	@Test
	void mismatchedAccountsAndBothProductIdentifiersAreRejectedIndividually() {
		var result = ingest(success(), row("wrong", "P0000PBU", "3490115053", "SUCCESS", "[성공] 완료"),
			row("seller-g", "P0000XXX", "3490115053", "SUCCESS", "[성공] 완료"),
			row("seller-g", "P0000PBU", "349011505", "SUCCESS", "[성공] 완료"));
		assertThat(result.saved()).isEqualTo(1);
		assertThat(result.rejected()).isEqualTo(3);
		assertThat(events.count()).isEqualTo(1);
	}

	@Test
	void ambiguousConnectionIsRejectedWithoutGuessing() {
		var other = products.saveAndFlush(Product.create("SB-" + UUID.randomUUID(),
			new ProductCreateCommand("https://example.com/other", new BigDecimal("10000"), "상품", "original", "브랜드",
				"US",
				new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "html", "FOOD", true,
				1, new BigDecimal("20"), VendorType.IHB, null)));
		registrations.saveAndFlush(MarketRegistration.builder().productId(other.getId()).marketType(MarketType.CAFE24)
			.marketIdentifiers(registration.getMarketIdentifiers()).build());
		assertThat(ingest(success()).rejected()).isEqualTo(1);
		assertThat(events.count()).isZero();
	}

	@Test
	void soldOutUnstopFailureDoesNotDetachOrStartResaleAndKeepsRawReason() {
		String detail = "[실패] 연동된 쇼핑몰상품이 '진열/판매'상태일 때만 판매중지를 해제할 수 있습니다.";
		assertThat(ingest(row("seller-g", "P0000PBU", "3490115053", "FAILURE", detail)).saved()).isEqualTo(1);
		assertThat(events.findAll().getFirst().getReasonCode()).isEqualTo("SOURCE_STATE_REVIEW_REQUIRED");
		assertThat(events.findAll().getFirst().getDetail()).isEqualTo(detail);
		assertThat(registrations.findById(registration.getId()).orElseThrow().connectionStateFor(MarketType.GMARKET))
			.isEqualTo(MarketConnectionState.LINKED);
	}

	@Test
	void historyOrderUsesRemoteTimeAndDetachedConnectionStaysHistorical() {
		ingest(success());
		jdbc.update("update sb_market_registration set gmarket_connection_state='DETACHED_PROHIBITED' where id=?",
			registration.getId());
		assertThat(service.history(product.getId()).getFirst().currentConnection()).isFalse();
		// Recording later evidence never reattaches a prohibited connection.
		ingest(row("seller-g", "P0000PBU", "3490115053", "FAILURE", "[실패] 상세 확인 필요"));
		assertThat(events.count()).isEqualTo(2);
		assertThat(registrations.findById(registration.getId()).orElseThrow().connectionStateFor(MarketType.GMARKET))
			.isEqualTo(MarketConnectionState.DETACHED_PROHIBITED);
	}

	@Test
	void wrongMallAbortsBeforeStoringAnyRows() {
		var c = credentials.findByMarketType(MarketType.CAFE24).orElseThrow();
		c.setClientId("anothermall");
		credentials.saveAndFlush(c);
		assertThatThrownBy(() -> ingest(success())).isInstanceOf(IllegalArgumentException.class);
		assertThat(events.count()).isZero();
	}

	@Test
	void impossibleTimestampsOrForgedSuccessAreRejected() {
		assertThatThrownBy(() -> row("seller-g", "P0000PBU", "3490115053", "SUCCESS", "[실패] error"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> batch(List.of(success()), COMPLETED.minusSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void concurrentImportsSerializeAndStoreOnlyOneObservation() throws Exception {
		try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var start = new java.util.concurrent.CountDownLatch(1);
			var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
				start.await();
				return ingest(success());
			})).toList();
			start.countDown();
			int saved = 0, duplicates = 0;
			for (var future : futures) {
				var result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
				saved += result.saved();
				duplicates += result.duplicate();
				assertThat(result.rejected()).isZero();
			}
			assertThat(saved).isEqualTo(1);
			assertThat(duplicates).isEqualTo(1);
			assertThat(events.count()).isEqualTo(1);
		}
	}

	@Test
	void lateCaptureOfOlderTransferDoesNotReplaceMoreRecentHistory() {
		ingest(success());
		var older = new MarketPlusTransmissionService.Row(MarketType.GMARKET, "seller-g", "10186", "P0000PBU",
			"3490115053",
			"상품수정", "FAILURE", "[실패] older", COMPLETED.minusSeconds(120), COMPLETED.minusSeconds(60));
		service.ingest(batch(List.of(older), COMPLETED.plusSeconds(120)), "admin");
		assertThat(service.history(product.getId()).getFirst().observation().getOutcome()).isEqualTo("SUCCESS");
	}

	@Test
	void accountReplacementMakesPreviouslyStoredObservationHistorical() {
		ingest(success());
		var c = credentials.findByMarketType(MarketType.CAFE24).orElseThrow();
		c.setClientId("anothermall");
		credentials.saveAndFlush(c);
		assertThat(service.history(product.getId()).getFirst().currentConnection()).isFalse();
	}

	MarketPlusTransmissionService.Summary summary() {
		return service.summaries(registrations.findByProductId(product.getId())).getOrDefault(product.getId(), Map.of())
			.get(MarketType.GMARKET);
	}

	@Test
	void latestFailureAppearsInGridSummaryWithoutModifyingRegistration() {
		ingest(row("seller-g", "P0000PBU", "3490115053", "FAILURE", "[실패] 상세 확인 필요"));
		assertThat(summary().outcome()).isEqualTo("FAILURE");
		assertThat(summary().completedAt()).isEqualTo(COMPLETED);
		assertThat(summary().detail()).contains("상세 확인 필요");
		assertThat(registrations.findById(registration.getId()).orElseThrow().getRevision())
			.isEqualTo(registration.getRevision());
	}

	@Test
	void conflictingMinuteResultsStayUnknownRegardlessOfImportOrder() {
		ingest(row("seller-g", "P0000PBU", "3490115053", "FAILURE", "[실패] error"));
		ingest(success());
		assertThat(summary().outcome()).isEqualTo("CONFLICT");
		events.deleteAll();
		ingest(success());
		ingest(row("seller-g", "P0000PBU", "3490115053", "FAILURE", "[실패] error"));
		assertThat(summary().outcome()).isEqualTo("CONFLICT");
	}

	@Test
	void laterSuccessReplacesEarlierFailureOfSameOperationOnly() {
		ingest(row("seller-g", "P0000PBU", "3490115053", "FAILURE", "[실패] error"));
		var later = new MarketPlusTransmissionService.Row(MarketType.GMARKET, "seller-g", "10186", "P0000PBU",
			"3490115053",
			"상품수정", "SUCCESS", "[성공] 완료", COMPLETED.plusSeconds(60), COMPLETED.plusSeconds(60));
		service.ingest(batch(List.of(later), COMPLETED.plusSeconds(120)), "admin");
		assertThat(summary().outcome()).isEqualTo("SUCCESS");
		// An older, later-imported failure cannot take over the summary.
		var older = new MarketPlusTransmissionService.Row(MarketType.GMARKET, "seller-g", "10186", "P0000PBU",
			"3490115053",
			"상품수정", "FAILURE", "[실패] older", COMPLETED.minusSeconds(60), COMPLETED.minusSeconds(60));
		service.ingest(batch(List.of(older), COMPLETED.plusSeconds(180)), "admin");
		assertThat(summary().outcome()).isEqualTo("SUCCESS");
	}

	@Test
	void successfulDifferentOperationNeverClearsModificationFailure() {
		ingest(row("seller-g", "P0000PBU", "3490115053", "FAILURE", "[실패] error"));
		var other = new MarketPlusTransmissionService.Row(MarketType.GMARKET, "seller-g", "10186", "P0000PBU",
			"3490115053",
			"상품등록", "SUCCESS", "[성공] 완료", COMPLETED.plusSeconds(60), COMPLETED.plusSeconds(60));
		service.ingest(batch(List.of(other), COMPLETED.plusSeconds(120)), "admin");
		assertThat(summary().outcome()).isEqualTo("FAILURE");
	}

	@Test
	void detachedOrReplacedConnectionNeverUsesOldSummary() {
		ingest(success());
		jdbc.update("update sb_market_registration set gmarket_connection_state='DETACHED_PROHIBITED' where id=?",
			registration.getId());
		assertThat(summary()).isNull();
		jdbc.update(
			"update sb_market_registration set gmarket_connection_state='LINKED',market_identifiers=? where id=?",
			"{\"product_no\":\"10186\",\"product_code\":\"P0000PBU\",\"gmarket_goodsNo\":\"newid\"}",
			registration.getId());
		assertThat(summary()).isNull();
	}

	void observe(MarketType market, String operation, String outcome, int seconds) {
		boolean gmarket = market == MarketType.GMARKET;
		Instant time = COMPLETED.plusSeconds(seconds);
		var observation = new MarketPlusTransmissionService.Row(market, gmarket ? "seller-g" : "seller-a",
			"10186", "P0000PBU", gmarket ? "3490115053" : "D888859044", operation, outcome,
			outcome.equals("SUCCESS") ? "[성공] 완료" : "[실패] 확인 필요", time, time);
		assertThat(service.ingest(batch(List.of(observation), COMPLETED.plusSeconds(600)), "admin").saved())
			.isEqualTo(1);
	}

	List<Long> search(MarketPlusIssueFilter filter) {
		return products.findAll(ProductSpecifications.matching(
			com.sbshop.agent.core.domain.product.dto.ProductSearchCondition.builder().marketPlusIssue(filter).build(),
			service.requireSearchScope())).stream().map(Product::getId).toList();
	}

	void assertIssues(boolean failure, boolean conflict) {
		assertThat(search(MarketPlusIssueFilter.ANY_ISSUE))
			.containsExactlyElementsOf(failure || conflict ? List.of(product.getId()) : List.of());
		assertThat(search(MarketPlusIssueFilter.FAILURE))
			.containsExactlyElementsOf(failure ? List.of(product.getId()) : List.of());
		assertThat(search(MarketPlusIssueFilter.CONFLICT))
			.containsExactlyElementsOf(conflict ? List.of(product.getId()) : List.of());
		var summaries = service.summaries(registrations.findAll()).getOrDefault(product.getId(), Map.of()).values();
		assertThat(summaries.stream().anyMatch(s -> s.outcome().equals("FAILURE"))).isEqualTo(failure);
		assertThat(summaries.stream().anyMatch(s -> s.outcome().equals("CONFLICT"))).isEqualTo(conflict);
	}

	@Test
	void searchUsesRemoteCompletionTimeAndOperationRatherThanImportTime() {
		assertIssues(false, false);
		observe(MarketType.GMARKET, "상품수정", "FAILURE", 0);
		observe(MarketType.GMARKET, "상품등록", "SUCCESS", 60);
		assertIssues(true, false);
		observe(MarketType.GMARKET, "상품수정", "SUCCESS", 120);
		observe(MarketType.GMARKET, "상품수정", "FAILURE", -60);
		assertIssues(false, false);
	}

	@Test
	void conflictTakesPrecedenceWithinMarketButOtherMarketFailureRemainsSearchable() {
		observe(MarketType.GMARKET, "상품수정", "FAILURE", 0);
		observe(MarketType.GMARKET, "상품수정", "SUCCESS", 0);
		observe(MarketType.GMARKET, "상품등록", "FAILURE", 60);
		assertIssues(false, true);
		observe(MarketType.AUCTION, "상품수정", "FAILURE", 120);
		assertIssues(true, true);
		observe(MarketType.GMARKET, "상품수정", "SUCCESS", 180);
		assertIssues(true, false);
	}

	@Test
	void parentOrChildDetachmentExcludesEvidenceAndIdentifierReplacementDoesNotReviveIt() throws Exception {
		observe(MarketType.GMARKET, "상품수정", "FAILURE", 0);
		for (String column : List.of("connection_state", "gmarket_connection_state")) {
			jdbc.update("update sb_market_registration set " + column + "='DETACHED_PROHIBITED' where id=?",
				registration.getId());
			assertIssues(false, false);
			jdbc.update("update sb_market_registration set " + column + "='LINKED' where id=?", registration.getId());
			assertIssues(true, false);
		}
		for (String key : List.of("product_no", "product_code", "gmarket_goodsNo")) {
			var identifiers = new ObjectMapper().readTree(registration.getMarketIdentifiers());
			((com.fasterxml.jackson.databind.node.ObjectNode)identifiers).put(key, "replacement");
			jdbc.update("update sb_market_registration set market_identifiers=? where id=?", identifiers.toString(),
				registration.getId());
			assertIssues(false, false);
		}
	}

	@Test
	void anotherAccountShopOrProductCannotProvideCurrentEvidenceOrClearFailure() {
		observe(MarketType.GMARKET, "상품수정", "FAILURE", 0);
		long failureId = events.findAll().getFirst().getId();
		observe(MarketType.GMARKET, "상품수정", "SUCCESS", 60);
		long successId = events.findAll().stream().mapToLong(MarketPlusTransmission::getId).max().orElseThrow();
		for (String change : List.of("seller_account='former-seller'", "mall_id='former-mall'", "shop_no=2",
			"product_id=-1")) {
			jdbc.update(
				"update sb_marketplus_transmission set seller_account='seller-g',mall_id='testmall',shop_no=1,product_id=? where id=?",
				product.getId(), successId);
			jdbc.update("update sb_marketplus_transmission set " + change + " where id=?", successId);
			assertIssues(true, false);
			jdbc.update("update sb_marketplus_transmission set " + change + " where id=?", failureId);
			assertIssues(false, false);
			assertThat(service.history(product.getId())).allMatch(item -> !item.currentConnection());
			jdbc.update(
				"update sb_marketplus_transmission set seller_account='seller-g',mall_id='testmall',shop_no=1,product_id=? where id=?",
				product.getId(), failureId);
		}
	}

	@Test
	void exactScalarIdentifiersAreRequiredForBothSearchAndSummary() {
		observe(MarketType.GMARKET, "상품수정", "FAILURE", 0);
		for (String identifiers : List.of("invalid", "{}",
			"{\"previousIdentifiers\":[" + registration.getMarketIdentifiers() + "]}",
			"{\"product_no\":10186,\"product_code\":\"P0000PBU\",\"gmarket_goodsNo\":[]}",
			"{\"product_no\":10186,\"product_code\":\"P0000PBU\",\"gmarket_goodsNo\":\" 3490115053\"}")) {
			jdbc.update("update sb_market_registration set market_identifiers=? where id=?", identifiers,
				registration.getId());
			assertIssues(false, false);
		}
		jdbc.update("update sb_market_registration set market_identifiers=? where id=?",
			"{\"product_no\":10186,\"product_code\":\"P0000PBU\",\"gmarket_goodsNo\":3490115053}",
			registration.getId());
		assertIssues(true, false);
	}

	@Test
	void booleanIdentifierNeverMatchesLiteralTextInAnObservation() {
		observe(MarketType.GMARKET, "상품수정", "FAILURE", 0);
		jdbc.update("update sb_marketplus_transmission set external_id='true'");
		jdbc.update("update sb_market_registration set market_identifiers=? where id=?",
			"{\"product_no\":10186,\"product_code\":\"P0000PBU\",\"gmarket_goodsNo\":true}", registration.getId());
		assertIssues(false, false);
		assertThat(service.history(product.getId())).allMatch(item -> !item.currentConnection());
		assertThat(ingest(row("seller-g", "P0000PBU", "true", "FAILURE", "[실패] 확인 필요")).rejected()).isEqualTo(1);
	}

	@Test
	void compoundIssueSearchCountsBeforePaginationAndNeverDuplicatesProducts() {
		observe(MarketType.GMARKET, "상품수정", "FAILURE", 0);
		observe(MarketType.AUCTION, "상품수정", "FAILURE", 0);
		var condition = com.sbshop.agent.core.domain.product.dto.ProductSearchCondition.builder()
			.marketPlusIssue(MarketPlusIssueFilter.ANY_ISSUE).vendors(List.of(VendorType.IHB))
			.brands(List.of("브랜드")).sbCodes(List.of(product.getSbCode()))
			.registeredMarkets(List.of(MarketType.GMARKET)).missingMarkets(List.of(MarketType.ELEVEN_STREET)).build();
		var spec = ProductSpecifications.matching(condition, service.requireSearchScope());
		var first = products.findAll(spec, org.springframework.data.domain.PageRequest.of(0, 1));
		var second = products.findAll(spec, org.springframework.data.domain.PageRequest.of(1, 1));
		assertThat(first.getContent()).extracting(Product::getId).containsExactly(product.getId());
		assertThat(first.getTotalElements()).isEqualTo(1);
		assertThat(second.getContent()).isEmpty();
		assertThat(second.getTotalElements()).isEqualTo(1);
		assertThat(products.findAll(ProductSpecifications.matching(
			com.sbshop.agent.core.domain.product.dto.ProductSearchCondition.builder()
				.marketPlusIssue(MarketPlusIssueFilter.ANY_ISSUE)
				.vendors(List.of(VendorType.AMZ)).build(),
			service.requireSearchScope()))).isEmpty();
	}

	@Test
	void inactiveCredentialIsExplicitlyUnavailableAndDoesNotStoreImports() {
		var c = credentials.findByMarketType(MarketType.CAFE24).orElseThrow();
		c.setIsActive(false);
		credentials.saveAndFlush(c);
		assertThat(service.readiness().ready()).isFalse();
		assertThat(service.readiness().reasons()).containsExactly("카페24 계정의 활성 상태 확인이 필요합니다.");
		assertThatThrownBy(service::requireSearchScope).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("활성 상태");
		assertThatThrownBy(() -> service.history(product.getId())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("활성 상태");
		assertThatThrownBy(() -> ingest(success())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("활성 상태");
		assertThat(events.count()).isZero();
	}
}
