package com.sbshop.agent.core.application.market.marketplus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;

@DataJpaTest(showSql = false, properties = {
	"spring.datasource.url=jdbc:h2:mem:mppublicchecks;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketPlusPublicCheckIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketPlusPublicCheckIntegrationTest {
	@org.springframework.test.context.DynamicPropertySource
	static void postgres(org.springframework.test.context.DynamicPropertyRegistry r) {
		String url = System.getenv("SBSHOP_MP_PUBLIC_TEST_POSTGRES_URL");
		if (url == null)
			return;
		if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sbshop_mp_public_check"))
			throw new IllegalArgumentException("Isolated test DB only");
		r.add("spring.datasource.url", () -> url);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.datasource.username", () -> "postgres");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketPlusPublicCheckRepository.class, MarketInspectionGateRepository.class})
	@Import({MarketPlusPublicCheckService.class, MarketPlusPublicObservationService.class})
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}

	@Autowired
	MarketPlusPublicCheckService service;
	@Autowired
	MarketPlusPublicCollectionRepository collections;
	@Autowired
	MarketPlusPublicCheckRepository checks;
	@Autowired
	MarketPlusPublicObservationRepository observations;
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
	@MockitoBean
	MarketPlusTransmissionService transmissions;
	Product product;
	MarketRegistration reg;

	@BeforeEach
	void setup() {
		checks.deleteAll();
		collections.deleteAll();
		observations.deleteAll();
		gates.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		when(transmissions.requireSearchScope()).thenReturn(new MarketPlusSearchScope("mall", "seller-g", "seller-a"));
		product = products.saveAndFlush(Product.create("SB-" + UUID.randomUUID(),
			new ProductCreateCommand("https://example.com/item", new BigDecimal("10000"), "상품", "original", "브랜드", "US",
				new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "html", "FOOD", true,
				1, new BigDecimal("20"), VendorType.IHB, null)));
		reg = registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId())
			.marketType(MarketType.CAFE24)
			.marketIdentifiers(
				"{\"product_no\":\"10186\",\"product_code\":\"P0000PBU\",\"auction_goodsNo\":\"D888859044\",\"gmarket_goodsNo\":\"3490115053\"}")
			.build());
	}

	MarketPlusPublicCheckService.Request request(String... markets) {
		return new MarketPlusPublicCheckService.Request(UUID.randomUUID().toString(), List.of(product.getId()),
			List.of(markets));
	}

	MarketPlusPublicCheckService.Claim claim() {
		return service.claim("relay").task();
	}

	MarketPlusPublicCheckService.Report good(MarketPlusPublicCheckService.Claim c) {
		var t = c.target();
		return new MarketPlusPublicCheckService.Report(c.leaseToken(),
			new MarketPlusPublicObservationService.Request(t.registrationId(), t.expectedRevision(),
				t.cafe24ProductNo(), t.cafe24ProductCode(),
				new MarketPlusPublicObservationService.Capture(1, "LIVE_CHROME_PUBLIC_MARKET",
					MarketType.valueOf(t.market()), t.externalId(), t.sellerAccount(), Instant.now(), t.publicUrl(),
					Map.of("salePrice", "95500"), "NOT_VERIFIED", "UNVERIFIED")),
			null, null, null);
	}

	MarketPlusPublicCheckService.Report failed(MarketPlusPublicCheckService.Claim c, Integer status, Long after) {
		return new MarketPlusPublicCheckService.Report(c.leaseToken(), null, "PUBLIC_PAGE_UNVERIFIED", status, after);
	}

	void release() {
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
		jdbc.update("update sb_marketplus_public_check set next_run_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
	}

	@Test
	void idempotentCreateIsOwnedAndPayloadIsImmutable() {
		var request = request("AUCTION");
		var first = service.create(request, "admin");
		assertThat(service.create(request, "admin").id()).isEqualTo(first.id());
		assertThat(checks.count()).isEqualTo(1);
		assertThatThrownBy(() -> service.create(
			new MarketPlusPublicCheckService.Request(request.requestId(), request.productIds(), List.of("GMARKET")),
			"admin")).hasMessageContaining("변경");
		assertThatThrownBy(() -> service.get(first.id(), "other")).hasMessageContaining("찾을");
		assertThat(service.recent("other")).isEmpty();
		assertThat(service.create(request, "other").id()).isNotEqualTo(first.id());
	}

	@Test
	void mixedMissingProductAndMissingConnectionAreExplicitSkippedItems() {
		jdbc.update("update sb_market_registration set market_identifiers=? where id=?",
			"{\"product_no\":\"10186\",\"product_code\":\"P0000PBU\",\"auction_goodsNo\":\"D888859044\"}", reg.getId());
		var r = service.create(new MarketPlusPublicCheckService.Request(UUID.randomUUID().toString(),
			List.of(product.getId(), 999999L), List.of("AUCTION", "GMARKET")), "admin");
		assertThat(r.items()).hasSize(4);
		assertThat(r.items()).filteredOn(i -> i.state().equals("SKIPPED")).hasSize(3);
		assertThat(r.items()).filteredOn(i -> i.state().equals("QUEUED")).hasSize(1);
	}

	@Test
	void successStoresOnlyObservationAndSameReportRecoversAfterProductChanges() {
		var r = service.create(request("AUCTION"), "request-owner");
		var c = claim();
		assertThat(claim()).isNull();
		var report = good(c);
		var result = service.report(c.taskId(), report, "relay");
		assertThat(result.state()).isEqualTo("OBSERVED");
		assertThat(observations.count()).isEqualTo(1);
		assertThat(observations.findAll().getFirst().getActor()).isEqualTo("request-owner");
		assertThat(products.findById(product.getId()).orElseThrow().getRevision()).isEqualTo(product.getRevision());
		assertThat(registrations.findById(reg.getId()).orElseThrow().getRevision()).isEqualTo(reg.getRevision());
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		assertThat(service.report(c.taskId(), report, "relay").observationId()).isEqualTo(result.observationId());
		assertThat(observations.count()).isEqualTo(1);
		assertThat(service.get(r.id(), "request-owner").items().getFirst().values()).containsEntry("salePrice",
			"95500");
	}

	@Test
	void staleProductBeforeClaimOrConnectionBeforeReportNeverIngests() {
		var r = service.create(request("AUCTION"), "admin");
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		assertThat(claim()).isNull();
		assertThat(service.get(r.id(), "admin").items().getFirst().state()).isEqualTo("STALE");
		r = service.create(request("AUCTION"), "admin");
		var c = claim();
		jdbc.update("update sb_market_registration set revision=revision+1 where id=?", reg.getId());
		assertThat(service.report(c.taskId(), good(c), "relay").state()).isEqualTo("STALE");
		assertThat(observations.count()).isZero();
	}

	@Test
	void changedMallAndSellerNeverReuseCapturedContext() {
		var r = service.create(request("AUCTION"), "admin");
		when(transmissions.requireSearchScope())
			.thenReturn(new MarketPlusSearchScope("other-mall", "seller-g", "seller-a"));
		assertThat(claim()).isNull();
		assertThat(service.get(r.id(), "admin").items().getFirst().state()).isEqualTo("STALE");
	}

	@Test
	void unknownPageRetriesThreeTimesThenEndsUnverifiedAndRetainsEveryReason() {
		var r = service.create(request("AUCTION"), "admin");
		for (int i = 1; i <= 3; i++) {
			release();
			var c = claim();
			var item = service.report(c.taskId(), failed(c, null, null), "relay");
			assertThat(item.state()).isEqualTo(i == 3 ? "FAILED_UNVERIFIED" : "RETRY_WAIT");
			assertThat(item.reason()).contains("삭제로 판단하지");
		}
		release();
		assertThat(claim()).isNull();
		var item = service.get(r.id(), "admin").items().getFirst();
		assertThat(item.events()).hasSize(3);
		assertThat(observations.count()).isZero();
	}

	@Test
	void actual429DefersAllSameMarketRequestsAndRetryAfterIsHonored() {
		service.create(request("AUCTION"), "admin");
		service.create(request("AUCTION"), "admin");
		var c = claim();
		var before = Instant.now();
		service.report(c.taskId(), failed(c, 429, 600L), "relay");
		assertThat(gates.findById("AUCTION_PUBLIC_READ").orElseThrow().getNextAllowedAt())
			.isAfter(before.plusSeconds(590));
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=? where id=?",
			java.sql.Timestamp.from(Instant.EPOCH), "MARKETPLUS_PUBLIC_BROWSER");
		assertThat(claim()).isNull();
	}

	@Test
	void aLongRateLimitedBacklogDoesNotStarveTheOtherMarket() {
		for (int i = 0; i < 101; i++)
			service.create(request("AUCTION"), "admin");
		service.create(request("GMARKET"), "admin");
		var first = claim();
		service.report(first.taskId(), failed(first, 429, 600L), "relay");
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=? where id=?",
			java.sql.Timestamp.from(Instant.EPOCH), "MARKETPLUS_PUBLIC_BROWSER");
		assertThat(claim().target().market()).isEqualTo("GMARKET");
	}

	@Test
	void crashedClaimIsReclaimedWithNewLeaseAndOldWorkerCannotOverwrite() {
		service.create(request("AUCTION"), "admin");
		var first = claim();
		release();
		var second = claim();
		assertThat(second.taskId()).isEqualTo(first.taskId());
		assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
		assertThatThrownBy(() -> service.report(first.taskId(), good(first), "relay")).hasMessageContaining("임대");
		assertThatThrownBy(() -> service.report(second.taskId(), good(second), "other-worker"))
			.hasMessageContaining("작업자");
		assertThat(service.report(second.taskId(), good(second), "relay").state()).isEqualTo("OBSERVED");
		assertThat(checks.findById(second.taskId()).orElseThrow().getAttempts()).isEqualTo(2);
	}

	@Test
	void late429FromReclaimedTasksOldIssuedLeaseDefersWithoutStealingNewLease() {
		service.create(request("AUCTION"), "admin");
		var old = claim();
		release();
		var current = claim();
		Instant before = Instant.now();
		var result = service.report(old.taskId(), failed(old, 429, 600L), "relay");
		assertThat(result.state()).isEqualTo("RUNNING");
		assertThat(gates.findById("AUCTION_PUBLIC_READ").orElseThrow().getNextAllowedAt())
			.isAfter(before.plusSeconds(590));
		assertThat(gates.findById("MARKETPLUS_PUBLIC_BROWSER").orElseThrow().getLeaseToken())
			.isEqualTo(current.leaseToken());
		assertThat(checks.findById(old.taskId()).orElseThrow().getLeaseToken()).isEqualTo(current.leaseToken());
		assertThat(service.report(current.taskId(), good(current), "relay").state()).isEqualTo("OBSERVED");
		assertThat(gates.findById("AUCTION_PUBLIC_READ").orElseThrow().getNextAllowedAt())
			.isAfter(before.plusSeconds(590));
	}

	@Test
	void fabricatedOrWrongWorkerOldTokenCannotChangeCooldown() {
		service.create(request("AUCTION"), "admin");
		var old = claim();
		release();
		claim();
		Instant before = gates.findById("AUCTION_PUBLIC_READ").orElseThrow().getNextAllowedAt();
		var forged = new MarketPlusPublicCheckService.Report(UUID.randomUUID().toString(), null, "HTTP_ERROR", 429,
			600L);
		assertThatThrownBy(() -> service.report(old.taskId(), forged, "relay")).hasMessageContaining("임대");
		assertThatThrownBy(() -> service.report(old.taskId(), failed(old, 429, 600L), "other-worker"))
			.hasMessageContaining("작업자");
		assertThat(gates.findById("AUCTION_PUBLIC_READ").orElseThrow().getNextAllowedAt()).isEqualTo(before);
	}

	@Test
	void expiredCrashesHaveSameBoundedAttemptLimit() {
		var r = service.create(request("AUCTION"), "admin");
		for (int i = 0; i < 3; i++) {
			release();
			assertThat(claim()).isNotNull();
		}
		release();
		assertThat(claim()).isNull();
		assertThat(service.get(r.id(), "admin").items().getFirst().state()).isEqualTo("FAILED_UNVERIFIED");
	}

	@Test
	void wrongObservationAndInvalidDtoNeverWrite() {
		service.create(request("AUCTION"), "admin");
		var c = claim();
		var good = good(c);
		var target = c.target();
		var wrong = new MarketPlusPublicObservationService.Request(target.registrationId(),
			target.expectedRevision() + 1, target.cafe24ProductNo(), target.cafe24ProductCode(),
			good.observation().observation());
		assertThatThrownBy(() -> service.report(c.taskId(),
			new MarketPlusPublicCheckService.Report(c.leaseToken(), wrong, null, null, null), "relay"))
			.hasMessageContaining("다릅니다");
		assertThat(observations.count()).isZero();
		assertThatThrownBy(() -> mapper.readValue(
			"{\"leaseToken\":\"" + c.leaseToken() + "\",\"errorCode\":\"HTTP_ERROR\",\"httpStatus\":429.5}",
			MarketPlusPublicCheckService.Report.class)).hasMessageContaining("정수");
		assertThatThrownBy(
			() -> new MarketPlusPublicCheckService.Report(c.leaseToken(), null, "PUBLIC_PAGE_UNVERIFIED", null, 100L))
			.hasMessageContaining("HTTP");
		assertThatThrownBy(() -> mapper.readValue(
			"{\"requestId\":\"" + UUID.randomUUID() + "\",\"productIds\":[1.5],\"markets\":[\"AUCTION\"]}",
			MarketPlusPublicCheckService.Request.class)).hasMessageContaining("정수");
	}

	@Test
	void simultaneousSameRequestProducesOneCollectionAndOneTask() throws Exception {
		var r = request("AUCTION");
		service.claim("relay"); // initialize durable gates before concurrent requests
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> service.create(r, "admin"));
			var second = pool.submit(() -> service.create(r, "admin"));
			assertThat(first.get(10, TimeUnit.SECONDS).id()).isEqualTo(second.get(10, TimeUnit.SECONDS).id());
		}
		assertThat(collections.count()).isEqualTo(1);
		assertThat(checks.count()).isEqualTo(1);
	}
}
