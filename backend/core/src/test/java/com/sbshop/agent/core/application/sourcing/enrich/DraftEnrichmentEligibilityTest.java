package com.sbshop.agent.core.application.sourcing.enrich;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sbshop.agent.core.application.sourcing.port.ProductDetailCrawlerPort;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DraftEnrichmentEligibilityTest {
	@Mock
	private ProductDetailCrawlerPort detailCrawler;
	@Mock
	private DraftPersistTxService persistence;
	@InjectMocks
	private DraftEnrichmentUseCase enrichment;

	@Test
	void blockedCandidateCannotBecomeAnAutomaticallyApprovedDraft() {
		SourcingCandidate candidate = scored();
		candidate.applyCustomsVerdict(CustomsVerdict.BLOCKED, "차단 성분", "ingredient");
		expectRejected(candidate);
	}

	@Test
	void excludedCandidateCannotBypassDiscoveryFilters() {
		SourcingCandidate candidate = scored();
		candidate.exclude("품절");
		expectRejected(candidate);
	}

	@Test
	void alreadyDraftedCandidateCannotGenerateDuplicateDrafts() {
		SourcingCandidate candidate = scored();
		candidate.markDrafted();
		expectRejected(candidate);
	}

	private void expectRejected(SourcingCandidate candidate) {
		assertThatThrownBy(() -> enrichment.enrich(candidate, SourcingConfig.createDefault()))
			.isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(detailCrawler, persistence);
	}

	private SourcingCandidate scored() {
		SourcingCandidate candidate = SourcingCandidate.builder().vendor(VendorType.IHB)
			.externalId("1").sourceUrl("https://kr.iherb.com/pr/item/1").nameKo("상품").build();
		candidate.applyScore(BigDecimal.TEN, "{}", BigDecimal.TEN, BigDecimal.TEN);
		candidate.applyCustomsVerdict(CustomsVerdict.PASS, "", "ingredient");
		return candidate;
	}
}
