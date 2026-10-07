package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;

import com.sbshop.agent.core.application.sourcing.enrich.DraftPersistTxService;
import com.sbshop.agent.core.application.sourcing.publish.DraftPublishTxService;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import com.sbshop.agent.core.domain.sourcing.enums.DraftStatus;
import com.sbshop.agent.core.domain.sourcing.repository.ProductDraftRepository;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SourcingDraftClaimPostgresTest.TestApp.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SourcingDraftClaimPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@SpringBootApplication
	@EntityScan(basePackageClasses = ProductDraft.class)
	@EnableJpaRepositories(basePackageClasses = ProductDraftRepository.class)
	@Import({DraftPublishTxService.class, DraftPersistTxService.class})
	static class TestApp {}

	@Autowired
	ProductDraftRepository drafts;
	@Autowired
	SourcingCandidateRepository candidates;
	@Autowired
	DraftPublishTxService publishing;
	@Autowired
	DraftPersistTxService enriching;

	@Test
	void concurrentPublishClaimsHaveExactlyOneWinnerOnPostgres() throws Exception {
		ProductDraft draft = ProductDraft.builder().baseNameKo("동시 등록 테스트").build();
		draft.applyEnrichment("상세", "[]", "");
		Long id = drafts.saveAndFlush(draft).getId();
		CountDownLatch start = new CountDownLatch(1);
		Callable<Boolean> claim = () -> {
			start.await(5, TimeUnit.SECONDS);
			try {
				publishing.markPublishing(id);
				return true;
			} catch (IllegalStateException expected) {
				return false;
			}
		};
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(claim);
			var second = pool.submit(claim);
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(drafts.findById(id).orElseThrow().getDraftStatus()).isEqualTo(DraftStatus.PUBLISHING);
	}

	@Test
	void rejectedDuplicateCandidateDoesNotPersistAnExtraDraftOnPostgres() {
		SourcingCandidate candidate = SourcingCandidate.builder().vendor(VendorType.IHB)
			.externalId("test-claim").sourceUrl("https://example.test/item").build();
		candidate.applyScore(BigDecimal.TEN, "{}", BigDecimal.TEN, BigDecimal.TEN);
		candidate.applyCustomsVerdict(CustomsVerdict.PASS, "", "ingredients");
		Long id = candidates.saveAndFlush(candidate).getId();
		enriching.saveAndMarkDrafted(ProductDraft.builder().candidateId(id).baseNameKo("first").build(), id);
		assertThatThrownBy(() -> enriching.saveAndMarkDrafted(
			ProductDraft.builder().candidateId(id).baseNameKo("duplicate").build(), id))
			.isInstanceOf(IllegalStateException.class);
		assertThat(drafts.findByCandidateId(id)).hasSize(1);
	}
}
