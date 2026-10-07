package com.sbshop.agent.core.application.sourcing.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.application.sourcing.SourcingQueryService;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import com.sbshop.agent.core.application.sourcing.enrich.DraftPersistTxService;
import java.math.BigDecimal;
import com.sbshop.agent.core.domain.sourcing.enums.DraftStatus;
import com.sbshop.agent.core.domain.sourcing.repository.ProductDraftRepository;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@ContextConfiguration(classes = DraftPublishClaimIntegrationTest.TestApp.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DraftPublishClaimIntegrationTest {
	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain.sourcing")
	@EnableJpaRepositories(basePackageClasses = ProductDraftRepository.class)
	@Import({DraftPublishTxService.class, DraftPersistTxService.class, SourcingQueryService.class,
		SourcingConfigService.class, ObjectMapper.class})
	static class TestApp {}

	@Autowired
	private ProductDraftRepository drafts;
	@Autowired
	private DraftPublishTxService publishing;
	@Autowired
	private DraftPersistTxService enriching;
	@Autowired
	private SourcingCandidateRepository candidates;
	@Autowired
	private SourcingQueryService queries;

	@BeforeEach
	void clean() {
		drafts.deleteAll();
		candidates.deleteAll();
	}

	@Test
	void onlyOneConcurrentRequestCanClaimTheDraft() throws Exception {
		ProductDraft draft = ProductDraft.builder().baseNameKo("상품").build();
		draft.applyEnrichment("상세", "[]", "");
		Long id = drafts.saveAndFlush(draft).getId();
		CountDownLatch start = new CountDownLatch(1);
		Callable<Boolean> claim = () -> {
			start.await(5, TimeUnit.SECONDS);
			try {
				publishing.markPublishing(id);
				return true;
			} catch (IllegalStateException e) {
				return false;
			}
		};
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(claim);
			var second = pool.submit(claim);
			start.countDown();
			assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		}
		assertThat(drafts.findById(id).orElseThrow().getDraftStatus()).isEqualTo(DraftStatus.PUBLISHING);
	}

	@Test
	void duplicateDraftSaveIsRejectedWithoutCreatingAnotherDraft() {
		SourcingCandidate candidate = SourcingCandidate.builder().vendor(VendorType.IHB)
			.externalId("1").sourceUrl("https://kr.iherb.com/pr/item/1").build();
		candidate.applyScore(BigDecimal.TEN, "{}", BigDecimal.TEN, BigDecimal.TEN);
		candidate.applyCustomsVerdict(CustomsVerdict.PASS, "", "ingredients");
		Long id = candidates.saveAndFlush(candidate).getId();
		enriching.saveAndMarkDrafted(ProductDraft.builder().candidateId(id).baseNameKo("first").build(), id);

		assertThatThrownBy(() -> enriching.saveAndMarkDrafted(
			ProductDraft.builder().candidateId(id).baseNameKo("duplicate").build(), id))
			.isInstanceOf(IllegalStateException.class);
		assertThat(drafts.findByCandidateId(id)).hasSize(1);
	}

	@Test
	void candidateBlockedDuringEnrichmentCannotBeSavedAsDraft() {
		SourcingCandidate candidate = SourcingCandidate.builder().vendor(VendorType.IHB)
			.externalId("2").sourceUrl("https://kr.iherb.com/pr/item/2").build();
		candidate.applyScore(BigDecimal.TEN, "{}", BigDecimal.TEN, BigDecimal.TEN);
		candidate.applyCustomsVerdict(CustomsVerdict.BLOCKED, "차단", "ingredients");
		Long id = candidates.saveAndFlush(candidate).getId();

		assertThatThrownBy(() -> enriching.saveAndMarkDrafted(
			ProductDraft.builder().candidateId(id).baseNameKo("blocked").build(), id))
			.isInstanceOf(IllegalStateException.class);
		assertThat(drafts.findByCandidateId(id)).isEmpty();
	}

	@Test
	void draftEditsCannotRestoreReadyWhilePublicationIsRunning() {
		ProductDraft draft = ProductDraft.builder().baseNameKo("상품").build();
		draft.applyEnrichment("상세", "[]", "");
		Long id = drafts.saveAndFlush(draft).getId();
		publishing.markPublishing(id);

		assertThatThrownBy(() -> queries.updateDraft(id, patch("변경")))
			.isInstanceOf(IllegalStateException.class);
		ProductDraft after = drafts.findById(id).orElseThrow();
		assertThat(after.getDraftStatus()).isEqualTo(DraftStatus.PUBLISHING);
		assertThat(after.getBaseNameKo()).isEqualTo("상품");
	}

	@Test
	void readyDraftStillAcceptsReviewEdits() {
		ProductDraft draft = ProductDraft.builder().baseNameKo("상품").build();
		draft.applyEnrichment("상세", "[]", "");
		Long id = drafts.saveAndFlush(draft).getId();

		queries.updateDraft(id, patch("검수 완료"));

		assertThat(drafts.findById(id).orElseThrow().getBaseNameKo()).isEqualTo("검수 완료");
	}

	@Test
	void linkedProductCommonFieldsMustBeEditedThroughTheProductWorkflow() {
		ProductDraft draft = ProductDraft.builder().baseNameKo("상품").marginRate(new BigDecimal("20.00")).build();
		draft.markFailed(41L);
		Long id = drafts.saveAndFlush(draft).getId();

		assertThatThrownBy(() -> queries.updateDraft(id, patch("초안만 바뀐 이름")))
			.isInstanceOf(IllegalStateException.class);
		assertThat(drafts.findById(id).orElseThrow().getBaseNameKo()).isEqualTo("상품");

		queries.updateDraft(id, new SourcingQueryService.DraftUpdate("상품", null, new BigDecimal("20"),
			null, null, null, null, null, null, null, null, null, java.util.List.of()));
	}

	@Test
	void successfulMarketFieldsCannotBeChangedByARetryThatSkipsThatMarket() {
		ProductDraft draft = ProductDraft.builder().baseNameKo("상품").build();
		draft.markFailed(41L);
		var market = com.sbshop.agent.core.domain.sourcing.MarketDraft.builder()
			.marketType(com.sbshop.agent.core.domain.order.enums.MarketType.COUPANG).productName("등록된 이름").build();
		market.markPublished("{\"sellerProductId\":\"123\"}");
		draft.putMarketDraft(market);
		Long id = drafts.saveAndFlush(draft).getId();

		assertThatThrownBy(() -> queries.updateDraft(id, marketPatch("수정 미반영 이름", true)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(drafts.findById(id).orElseThrow().getMarketDrafts().getFirst().getProductName()).isEqualTo("등록된 이름");

		queries.updateDraft(id, marketPatch("등록된 이름", false));
		assertThat(drafts.findById(id).orElseThrow().getMarketDrafts().getFirst().isEnabled()).isFalse();
	}

	private SourcingQueryService.DraftUpdate marketPatch(String name, boolean enabled) {
		return new SourcingQueryService.DraftUpdate(null, null, null, null, null, null,
			null, null, null, null, null, null, java.util.List.of(new SourcingQueryService.MarketDraftUpdate(
				"COUPANG", name, null, null, null, null, enabled)));
	}

	private SourcingQueryService.DraftUpdate patch(String name) {
		return new SourcingQueryService.DraftUpdate(name, null, null, null, null, null,
			null, null, null, null, null, null, java.util.List.of());
	}

	@Test
	void publishedDraftCannotBeClaimedAgain() {
		ProductDraft draft = ProductDraft.builder().baseNameKo("상품").build();
		draft.markPublished(41L);
		Long id = drafts.saveAndFlush(draft).getId();

		assertThatThrownBy(() -> publishing.markPublishing(id)).isInstanceOf(IllegalStateException.class);
		assertThat(drafts.findById(id).orElseThrow().getDraftStatus()).isEqualTo(DraftStatus.PUBLISHED);
	}
}
