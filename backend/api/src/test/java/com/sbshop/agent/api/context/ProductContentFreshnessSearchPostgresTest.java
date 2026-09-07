package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;

import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.content.*;
import com.sbshop.agent.core.domain.product.dto.*;
import com.sbshop.agent.core.domain.product.edit.ProductChangeTarget;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.infrastructure.repository.product.ProductReaderImpl;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = {ProductContentFreshnessSearchPostgresTest.TestApp.class, ProductReaderImpl.class})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ProductContentFreshnessSearchPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
	}

	// Explicit reader + test configuration keep this slice out of full-application discovery/scanning.
	@TestConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = ProductRepository.class)
	static class TestApp {}

	@Autowired
	EntityManager em;
	@Autowired
	ProductReader reader;
	@Autowired
	ProductRepository products;
	final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	@BeforeEach
	void registerFunctions() throws Exception {
		String sql = Files.readString(Path.of("../docs/ddl/2026-09-06-market-search-identifiers.sql"));
		em.unwrap(Session.class).doWork(connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute(sql);
			}
		});
	}

	Product product(String code) {
		var product = Product.create(code,
			new ProductCreateCommand("https://kr.iherb.com/pr/example/12345", new BigDecimal("10000"),
				"상품", "Original", "브랜드", "US", new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G,
				List.of(), List.of(), "본문", "FOOD", true, 3, new BigDecimal("20"), VendorType.IHB, null));
		return products.saveAndFlush(product);
	}

	ProductContentSnapshot snapshot(Product product, Instant collected, Instant imagesApplied, Instant detailApplied) {
		String collectionId = UUID.randomUUID().toString();
		em.persist(new ProductContentCollection(collectionId, UUID.randomUUID().toString(), "admin", collected,
			"[" + product.getId() + "]"));
		var snapshot = new ProductContentSnapshot(UUID.randomUUID().toString(), collectionId, product.getId(),
			product.getSbCode(),
			product.getRevision(), "f".repeat(64), product.getSourcingUrl(), "IHB", collected, "{}");
		snapshot.complete("{}", true, true, collected);
		if (imagesApplied != null)
			snapshot.applied(true, false, imagesApplied);
		if (detailApplied != null)
			snapshot.applied(false, true, detailApplied);
		em.persist(snapshot);
		return snapshot;
	}

	MarketRegistration registration(Product product, boolean detached, String identifiers) {
		var registration = MarketRegistration.builder().productId(product.getId()).sbProductId(product.getId())
			.marketType(MarketType.COUPANG).marketIdentifiers(identifiers).marketDetailedInfo("{}").build();
		registration.recordSyncError(SyncErrorType.VALIDATION_FAILED, "review required");
		if (detached)
			registration.detachConnection(MarketType.COUPANG, MarketConnectionState.DETACHED_DELETED);
		em.persist(registration);
		return registration;
	}

	Page<Product> search(ProductContentAgeField field, Integer days, String sort, int page, int size) {
		em.flush();
		return reader.search(ProductSearchCondition.builder().contentAgeField(field).contentAgeDays(days).build(),
			PageRequest.of(page, size, Sort.by(sort)));
	}

	@Test
	void ageUsesTheLatestAppliedValueAndKeepsRecentCollectionSeparate() {
		var never = product("NEVER");
		var collectedOnly = product("COLLECTED_ONLY");
		var oldImages = product("OLD_IMAGES");
		var recent = product("RECENT");
		snapshot(collectedOnly, now, null, null);
		snapshot(oldImages, now.minus(181, ChronoUnit.DAYS), now.minus(180, ChronoUnit.DAYS),
			now.minus(2, ChronoUnit.DAYS));
		snapshot(oldImages, now, null, null); // New collection must not imply fresh DB content.
		snapshot(recent, now.minus(181, ChronoUnit.DAYS), now.minus(180, ChronoUnit.DAYS),
			now.minus(180, ChronoUnit.DAYS));
		snapshot(recent, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
		var images = search(ProductContentAgeField.IMAGES, 90, "contentOldest", 0, 10);
		assertThat(images.getContent()).extracting(Product::getId).containsExactly(never.getId(), collectedOnly.getId(),
			oldImages.getId());
		assertThat(search(ProductContentAgeField.DETAIL_HTML, 90, "contentOldest", 0, 10).getContent())
			.extracting(Product::getId).containsExactly(never.getId(), collectedOnly.getId());
		assertThat(search(ProductContentAgeField.ANY, 90, "contentOldest", 0, 10).getTotalElements()).isEqualTo(3);
	}

	@Test
	void pagesAndCountUseAllProductsAndStableIdOrderingBeforeLimit() {
		var recent = product("RECENT");
		var old = product("OLD");
		var never1 = product("NEVER1");
		var never2 = product("NEVER2");
		snapshot(recent, now.minus(3, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
		snapshot(old, now.minus(181, ChronoUnit.DAYS), now.minus(180, ChronoUnit.DAYS),
			now.minus(170, ChronoUnit.DAYS));
		List<Long> ids = new ArrayList<>();
		for (int page = 0; page < 4; page++) {
			var result = search(ProductContentAgeField.ANY, null, "contentOldest", page, 1);
			assertThat(result.getTotalElements()).isEqualTo(4);
			assertThat(result.getTotalPages()).isEqualTo(4);
			ids.add(result.getContent().getFirst().getId());
		}
		assertThat(ids).containsExactly(never1.getId(), never2.getId(), old.getId(), recent.getId());
		assertThat(search(ProductContentAgeField.ANY, null, "contentOldest", 1, 1).getContent().getFirst().getId())
			.isEqualTo(never2.getId());
		assertThat(search(ProductContentAgeField.ANY, 90, "contentOldest", 0, 1).getTotalElements()).isEqualTo(3);
	}

	@Test
	void workspacePrioritizesPendingSourceIssuesAndActiveFailuresButNotDetachedErrorsOrStockout() {
		var stockout = product("STOCKOUT");
		stockout.updateStockStatus(StockStatus.OUT_OF_STOCK);
		var detached = product("DETACHED");
		registration(detached, true, "{\"sellerProductId\":\"11\"}");
		var noIdentifier = product("NO_IDENTIFIER");
		registration(noIdentifier, false, "{}");
		var pending = product("PENDING");
		var target = new ProductChangeTarget(1L, pending.getId(), 1L, 0L, "COUPANG", "{}");
		em.persist(target);
		var sourceFailed = product("SOURCE_FAILED");
		sourceFailed.recordCrawlFailure("source unavailable");
		var gone = product("GONE");
		gone.markSourceGone(SourceGoneReason.LINK_DEAD);
		var failed = product("ACTIVE_ERROR");
		registration(failed, false, "{\"sellerProductId\":\"22\"}");
		for (var p : List.of(pending, sourceFailed, gone, failed))
			snapshot(p, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
		var result = search(ProductContentAgeField.ANY, null, "workspacePriority", 0, 20);
		assertThat(result.getContent()).extracting(Product::getId).containsExactly(pending.getId(),
			sourceFailed.getId(), gone.getId(), failed.getId(),
			stockout.getId(), detached.getId(), noIdentifier.getId());
		target.priceOutcome("SYNCED");
		sourceFailed.recordCrawlSuccess();
		gone.clearSourceGone();
		var next = search(ProductContentAgeField.ANY, null, "workspacePriority", 0, 1);
		assertThat(next.getContent().getFirst().getId()).isEqualTo(failed.getId());
		assertThat(next.getTotalElements()).isEqualTo(7);
	}

	@Test
	void freshnessForThePageUsesOneAggregateQueryAndFailedNewerRowsDoNotEraseOlderSuccess() {
		var one = product("ONE");
		var two = product("TWO");
		var noHistory = product("NO_HISTORY");
		Instant old = now.minus(180, ChronoUnit.DAYS);
		snapshot(one, old, old, null);
		snapshot(one, now.minus(1, ChronoUnit.DAYS), null, now.minus(1, ChronoUnit.DAYS));
		var failed = snapshot(one, now, null, null);
		// A failed attempt has no successful collection timestamps.
		org.springframework.test.util.ReflectionTestUtils.setField(failed, "imagesCollectedAt", null);
		org.springframework.test.util.ReflectionTestUtils.setField(failed, "detailCollectedAt", null);
		failed.fail(ProductContentSnapshot.State.FAILED, "failed");
		snapshot(two, old, null, null);
		em.flush();
		em.clear();
		var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		var freshness = reader.findContentFreshness(List.of(one.getId(), two.getId(), noHistory.getId()));
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(freshness.get(one.getId())).isEqualTo(new ProductContentFreshness(now.minus(1, ChronoUnit.DAYS), old,
			now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS)));
		assertThat(freshness.get(two.getId())).isEqualTo(new ProductContentFreshness(old, null, old, null));
		assertThat(freshness).doesNotContainKey(noHistory.getId());
		statistics.clear();
		assertThat(reader.findContentFreshness(List.of())).isEmpty();
		assertThat(statistics.getPrepareStatementCount()).isZero();
	}

	@Test
	void explicitNormalSortsAndDefaultWorkspaceSortRemainAvailable() {
		var b = product("B");
		var a = product("A");
		a.update(ProductUpdateCommand.builder().salePrice(new BigDecimal("50000")).build());
		b.update(ProductUpdateCommand.builder().salePrice(new BigDecimal("10000")).build());
		b.recordCrawlFailure("source failed");
		em.flush();
		assertThat(reader.search(ProductSearchCondition.none(), PageRequest.of(0, 10)).getContent())
			.extracting(Product::getId).containsExactly(b.getId(), a.getId());
		assertThat(reader.search(ProductSearchCondition.none(), PageRequest.of(0, 10, Sort.by("sbCode"))).getContent())
			.extracting(Product::getSbCode).containsExactly("A", "B");
		assertThat(reader
			.search(ProductSearchCondition.none(), PageRequest.of(0, 10, Sort.by("priceInfo.salePrice").descending()))
			.getContent()).extracting(Product::getId).containsExactly(a.getId(), b.getId());
		assertThatThrownBy(() -> reader.search(ProductSearchCondition.none(),
			PageRequest.of(0, 10, Sort.by("workspacePriority").descending())))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void changingSourceUrlOrVendorMakesPreviousSourceFreshnessInapplicableWithoutDeletingHistory() {
		var unchanged = product("UNCHANGED");
		var changedUrl = product("CHANGED_URL");
		var changedVendor = product("CHANGED_VENDOR");
		for (var p : List.of(unchanged, changedUrl, changedVendor))
			snapshot(p, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
		changedUrl.update(ProductUpdateCommand.builder().sourceUrl("https://kr.iherb.com/pr/other/98765").build());
		changedVendor.update(ProductUpdateCommand.builder().vendor(VendorType.AMZ).build());
		em.flush();
		var freshness = reader
			.findContentFreshness(List.of(unchanged.getId(), changedUrl.getId(), changedVendor.getId()));
		assertThat(freshness).containsOnlyKeys(unchanged.getId());
		assertThat(search(ProductContentAgeField.ANY, 90, "contentOldest", 0, 10).getContent())
			.extracting(Product::getId)
			.containsExactly(changedUrl.getId(), changedVendor.getId());
		assertThat(search(ProductContentAgeField.ANY, null, "contentOldest", 0, 10).getContent())
			.extracting(Product::getId)
			.containsExactly(changedUrl.getId(), changedVendor.getId(), unchanged.getId());
		assertThat(em.createQuery("select count(s) from ProductContentSnapshot s", Long.class).getSingleResult())
			.isEqualTo(3);
	}
}
