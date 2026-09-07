package com.sbshop.agent.core.application.product.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.application.product.content.ProductContentData.*;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.content.*;
import com.sbshop.agent.core.domain.product.content.ProductContentSnapshot.State;
import com.sbshop.agent.core.domain.product.dto.*;
import com.sbshop.agent.core.domain.product.edit.*;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:productcontent;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductContentServiceIntegrationTest.TestApp.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductContentServiceIntegrationTest {
	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class})
	@Import({ProductContentService.class, ProductContentWorker.class, ProductEditService.class,
		ProductEditPlanner.class, ProductEditPolicy.class})
	static class TestApp {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}

	@Autowired
	ProductContentService service;
	@Autowired
	ProductContentWorker worker;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	ProductContentCollectionRepository collections;
	@Autowired
	ProductContentSnapshotRepository snapshots;
	@Autowired
	ProductContentReviewRepository reviews;
	@Autowired
	ProductContentLaneRepository lanes;
	@Autowired
	ProductChangeTargetRepository targets;
	@Autowired
	PlatformTransactionManager transactions;
	@MockitoSpyBean
	ProductChangeHistoryRepository histories;
	@MockitoBean
	ProductContentSource source;
	@MockitoBean
	MarketSalePriceResolver prices;
	TransactionTemplate tx;
	static final String OLD = "https://assets.example.com/old.jpg";
	static final String NEW = "https://assets.example.com/new.jpg";
	static final String ORIGINAL = "https://cloudinary.images-iherb.com/image/upload/f_auto/images/abc/abc123/l/1.jpg";

	@BeforeEach
	void before() {
		tx = new TransactionTemplate(transactions);
		reset(source, histories);
		targets.deleteAll();
		histories.deleteAll();
		reviews.deleteAll();
		snapshots.deleteAll();
		collections.deleteAll();
		lanes.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		lanes.saveAndFlush(new ProductContentLane("IHB"));
		for (String vendor : List.of("IHB", "VTB", "FTN", "COK"))
			lanes.saveAndFlush(new ProductContentLane("SOURCE_" + vendor));
		when(source.fetch(anyString())).thenReturn(new ProductContentSource.Fetch(List.of(ORIGINAL), List.of(NEW),
			"<p>새 소싱 설명</p>", true, true, List.of()));
	}

	Product product(VendorType vendor) {
		return tx.execute(s -> {
			Product product = Product.create("SB-" + UUID.randomUUID(),
				new ProductCreateCommand("https://kr.iherb.com/pr/example/12345", new BigDecimal("10000"),
					"기본 상품", "Original", "브랜드", "US", new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G,
					List.of(ORIGINAL), List.of(OLD), "이전 설명", "FOOD", true, 3, new BigDecimal("20"), vendor, null));
			return products.saveAndFlush(product);
		});
	}

	ProductContentService.Collection collect(Product... products) {
		return service.collect(new ProductContentService.CollectionRequest(UUID.randomUUID().toString(),
			Arrays.stream(products).map(Product::getId).toList()), "admin");
	}

	ProductContentService.Snapshot completed(Product product) {
		var collection = collect(product);
		worker.tick();
		return service.collection(collection.id(), "admin").items().getFirst();
	}

	ProductEditService.Review review(ProductContentService.Snapshot snapshot, Field... fields) {
		return service.review(new ProductContentService.ReviewRequest(
			List.of(new ProductContentService.Selection(snapshot.id(), List.of(fields)))), "admin");
	}

	void link(Product product) {
		registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId()).sbProductId(product.getId())
			.marketType(MarketType.COUPANG).marketIdentifiers("{\"sellerProductId\":\"123\"}").marketDetailedInfo("{}")
			.build());
	}

	@Test
	void collectionIsDurableIdempotentAndDoesNotFetchInRequestTransaction() {
		var product = product(VendorType.IHB);
		var request = new ProductContentService.CollectionRequest(UUID.randomUUID().toString(),
			List.of(product.getId()));
		var first = service.collect(request, "admin");
		assertThat(first.items().getFirst().state()).isEqualTo(State.QUEUED);
		assertThat(service.collect(request, "admin").id()).isEqualTo(first.id());
		assertThat(snapshots.count()).isEqualTo(1);
		verifyNoInteractions(source);
		assertThatThrownBy(() -> service
			.collect(new ProductContentService.CollectionRequest(request.requestId(), List.of(9999L)), "admin"))
			.isInstanceOf(ProductEditConflictException.class);
		assertThatThrownBy(() -> service.collection(first.id(), "someone-else")).isInstanceOf(RuntimeException.class);
		assertThat(service.history(product.getId(), "someone-else")).isEmpty();
	}

	@Test
	void partialBatchRetainsUnsupportedAndMissingProductsWithoutInventingSuccessfulCollection() {
		var product = product(VendorType.AMZ);
		var result = service.collect(
			new ProductContentService.CollectionRequest(UUID.randomUUID().toString(), List.of(product.getId(), 9999L)),
			"admin");
		assertThat(result.items()).extracting(ProductContentService.Snapshot::state)
			.containsExactlyInAnyOrder(State.UNSUPPORTED, State.FAILED);
		assertThat(result.items()).allMatch(item -> item.collectedAt() == null && item.appliedAt() == null);
		worker.tick();
		verifyNoInteractions(source);
	}

	@Test
	void collectionUsesFrozenInputsOutsideTransactionAndSelectionWritesOnlyReviewedFields() {
		var product = product(VendorType.IHB);
		String oldHtml = product.getDetailHtml();
		when(source.fetch(anyString())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new ProductContentSource.Fetch(List.of(ORIGINAL), List.of(NEW), "<p>새 소싱 설명</p>", true, true,
				List.of());
		});
		var snapshot = completed(product);
		assertThat(snapshot.state()).isEqualTo(State.READY);
		assertThat(snapshot.proposed().detailHtml()).contains(product.getProductName(), "새 소싱 설명", NEW);
		assertThat(products.findById(product.getId()).orElseThrow().getHostedImages()).containsExactly(OLD);
		assertThat(snapshot.collectedAt()).isNotNull();
		assertThat(snapshot.appliedAt()).isNull();
		var review = review(snapshot, Field.IMAGES);
		var saved = service.commit(review.reviewId(), "admin");
		assertThat(saved.items().getFirst().state()).isEqualTo("SAVED");
		assertThat(products.findById(product.getId()).orElseThrow().getHostedImages()).containsExactly(NEW);
		assertThat(products.findById(product.getId()).orElseThrow().getDetailHtml()).isEqualTo(oldHtml);
		assertThat(histories.count()).isEqualTo(1);
		assertThat(targets.count()).isZero();
		var after = snapshots.findById(snapshot.id()).orElseThrow();
		assertThat(after.getImagesAppliedAt()).isNotNull();
		assertThat(after.getDetailAppliedAt()).isNull();
		assertThat(service.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("SAVED");
		assertThat(histories.count()).isEqualTo(1);
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getImagesAppliedAt())
			.isEqualTo(after.getImagesAppliedAt());
	}

	@Test void emptyAndPartialResultsNeverOverwriteMissingContentOrAdvanceThatFieldsTimestamp() {
		when(source.fetch(anyString())).thenReturn(new ProductContentSource.Fetch(List.of(), List.of(), null, false, false, List.of("실패")));
		var failed = completed(product(VendorType.IHB));
		assertThat(failed.state()).isEqualTo(State.FAILED); assertThat(failed.collectedAt()).isNull();
		assertThat(failed.fields()).allMatch(field -> !field.available() && field.collectedAt() == null);
		tx.executeWithoutResult(s -> { for(String id:List.of("IHB","SOURCE_IHB")) ReflectionTestUtils.setField(lanes.findLocked(id).orElseThrow(), "nextAllowedAt", Instant.now().minusSeconds(1)); });
		when(source.fetch(anyString())).thenReturn(new ProductContentSource.Fetch(List.of(), List.of(), "<p>새 상세</p>", false, true, List.of("이미지 실패")));
		var partial = completed(product(VendorType.IHB));
		assertThat(partial.state()).isEqualTo(State.PARTIAL);
		assertThat(partial.fields().getFirst().collectedAt()).isNull();
		assertThat(partial.fields().getLast().collectedAt()).isNotNull();
		assertThat(review(partial, Field.IMAGES).items().getFirst().state()).isEqualTo(ProductEditPlanner.State.EXCLUDED);
	}

	@Test
	void linkedProductsKeepContentLockAndNewConnectionsAfterReviewConflict() {
		var product = product(VendorType.IHB);
		var snapshot = completed(product);
		var review = review(snapshot, Field.IMAGES, Field.DETAIL_HTML);
		link(product);
		assertThat(service.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
		assertThat(histories.count()).isZero();
		assertThat(service.collection(snapshots.findById(snapshot.id()).orElseThrow().getCollectionId(), "admin")
			.items().getFirst().fields())
			.allMatch(field -> !field.editable());
		tx.executeWithoutResult(s -> {
			for (String id : List.of("IHB", "SOURCE_IHB"))
				ReflectionTestUtils.setField(lanes.findLocked(id).orElseThrow(), "nextAllowedAt",
					Instant.now().minusSeconds(1));
		});
		var recaptured = completed(product);
		assertThat(review(recaptured, Field.DETAIL_HTML).items().getFirst().state())
			.isEqualTo(ProductEditPlanner.State.EXCLUDED);
	}

	@Test
	void ownershipExpiryAndRevisionAreRecheckedAtCommit() {
		var product = product(VendorType.IHB);
		var snapshot = completed(product);
		var review = review(snapshot, Field.DETAIL_HTML);
		assertThatThrownBy(() -> service.commit(review.reviewId(), "other"))
			.isInstanceOf(ProductEditConflictException.class);
		tx.executeWithoutResult(s -> {
			var stored = reviews.findById(review.reviewId()).orElseThrow();
			ReflectionTestUtils.setField(stored, "expiresAt", Instant.now().minusSeconds(1));
		});
		assertThat(service.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
		var second = review(snapshot, Field.IMAGES);
		tx.executeWithoutResult(s -> products.findForEdit(product.getId()).orElseThrow()
			.update(ProductUpdateCommand.builder().memo("another edit").build()));
		assertThat(service.commit(second.reviewId(), "admin").items().getFirst().state()).isEqualTo("CONFLICT");
		assertThat(histories.count()).isZero();
	}

	@Test
	void historyFailureRollsBackProductAndAppliedTimestampTogether() {
		var product = product(VendorType.IHB);
		var snapshot = completed(product);
		var review = review(snapshot, Field.IMAGES, Field.DETAIL_HTML);
		doThrow(new IllegalStateException("simulated history failure")).when(histories)
			.save(any(ProductChangeHistory.class));
		assertThat(service.commit(review.reviewId(), "admin").items().getFirst().state()).isEqualTo("FAILED");
		assertThat(products.findById(product.getId()).orElseThrow().getHostedImages()).containsExactly(OLD);
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getImagesAppliedAt()).isNull();
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getDetailAppliedAt()).isNull();
	}

	@Test
	void expiredLeaseAfterRestartBecomesVisibleFailureAndIsNotRepeatedSilently() {
		var snapshot = collect(product(VendorType.IHB)).items().getFirst();
		tx.executeWithoutResult(s -> {
			snapshots.findLocked(snapshot.id()).orElseThrow().claim(UUID.randomUUID().toString());
			lanes.findLocked("IHB").orElseThrow().claim(snapshot.id(), Instant.now().minusSeconds(700));
		});
		worker.tick();
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getState()).isEqualTo(State.FAILED);
		assertThat(snapshots.findById(snapshot.id()).orElseThrow().getCollectedAt()).isNull();
		verifyNoInteractions(source);
	}

	@Test
	void throttlingPausesOtherQueuedProductsDurably() {
		collect(product(VendorType.IHB), product(VendorType.IHB));
		when(source.fetch(anyString())).thenThrow(new ProductContentThrottledException());
		worker.tick();
		worker.tick();
		verify(source, times(1)).fetch(anyString());
		assertThat(lanes.findById("SOURCE_IHB").orElseThrow().getNextAllowedAt())
			.isAfter(Instant.now().plusSeconds(290));
		assertThat(snapshots.countByStateIn(List.of(State.FAILED))).isEqualTo(1);
		assertThat(snapshots.countByStateIn(List.of(State.QUEUED))).isEqualTo(1);
	}

	@Test
	void serverRetryAfterLongerThanFiveMinutesIsPreservedForTheWholeQueue() {
		collect(product(VendorType.IHB), product(VendorType.IHB));
		Instant serverAt = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		when(source.fetch(anyString())).thenThrow(new ProductContentThrottledException(serverAt));
		worker.tick();
		worker.tick();
		assertThat(lanes.findById("SOURCE_IHB").orElseThrow().getNextAllowedAt()).isEqualTo(serverAt);
		verify(source, times(1)).fetch(anyString());
	}

	@Test
	void safeSourceSchemaFailureIsVisibleAndKeepsSuccessAndApplicationTimestampsUnset() {
		var collection = collect(product(VendorType.IHB));
		when(source.fetch(anyString()))
			.thenThrow(new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_NAME_MISSING));
		worker.tick();
		var snapshot = service.collection(collection.id(), "admin").items().getFirst();
		assertThat(snapshot.state()).isEqualTo(State.FAILED);
		assertThat(snapshot.reason()).contains("SOURCE_NAME_MISSING", "displayName");
		assertThat(snapshot.collectedAt()).isNull();
		assertThat(snapshot.appliedAt()).isNull();
	}

	@Test
	void oneConflictedProductDoesNotRollBackAnotherProductsReviewedContent() {
		var first = product(VendorType.IHB);
		var firstSnapshot = completed(first);
		tx.executeWithoutResult(s -> {
			for (String id : List.of("IHB", "SOURCE_IHB"))
				ReflectionTestUtils.setField(lanes.findLocked(id).orElseThrow(), "nextAllowedAt",
					Instant.now().minusSeconds(1));
		});
		var second = product(VendorType.IHB);
		var secondSnapshot = completed(second);
		var reviewed = service.review(new ProductContentService.ReviewRequest(List.of(
			new ProductContentService.Selection(firstSnapshot.id(), List.of(Field.IMAGES)),
			new ProductContentService.Selection(secondSnapshot.id(), List.of(Field.IMAGES)))), "admin");
		tx.executeWithoutResult(s -> products.findForEdit(first.getId()).orElseThrow()
			.update(ProductUpdateCommand.builder().memo("edited").build()));
		var saved = service.commit(reviewed.reviewId(), "admin");
		assertThat(saved.items()).extracting(ProductEditService.CommitItem::state).containsExactly("CONFLICT", "SAVED");
		assertThat(products.findById(first.getId()).orElseThrow().getHostedImages()).containsExactly(OLD);
		assertThat(products.findById(second.getId()).orElseThrow().getHostedImages()).containsExactly(NEW);
		assertThat(histories.count()).isEqualTo(1);
	}

	@Test
	void generationUsesCapturedNameEvenWhenDatabaseChangesDuringCollection() {
		var product = product(VendorType.IHB);
		var collection = collect(product);
		tx.executeWithoutResult(s -> products.findForEdit(product.getId()).orElseThrow()
			.update(ProductUpdateCommand.builder().name("수집 후 바뀐 이름").build()));
		worker.tick();
		var snapshot = service.collection(collection.id(), "admin").items().getFirst();
		assertThat(snapshot.proposed().detailHtml()).contains(product.getProductName()).doesNotContain("수집 후 바뀐 이름");
		assertThat(snapshot.fields()).allMatch(field -> !field.editable());
	}

	@Test
	void lateWorkerCannotTurnAReplacedClaimIntoSuccessfulContent() {
		var collection = collect(product(VendorType.IHB));
		String snapshotId = collection.items().getFirst().id();
		when(source.fetch(anyString())).thenAnswer(invocation -> {
			tx.executeWithoutResult(
				s -> snapshots.findLocked(snapshotId).orElseThrow().claim(UUID.randomUUID().toString()));
			return new ProductContentSource.Fetch(List.of(ORIGINAL), List.of(NEW), "<p>늦은 응답</p>", true, true,
				List.of());
		});
		worker.tick();
		var snapshot = snapshots.findById(snapshotId).orElseThrow();
		assertThat(snapshot.getState()).isEqualTo(State.COLLECTING);
		assertThat(snapshot.getCollectedAt()).isNull();
		assertThat(snapshot.getProposed()).isNull();
	}
}
