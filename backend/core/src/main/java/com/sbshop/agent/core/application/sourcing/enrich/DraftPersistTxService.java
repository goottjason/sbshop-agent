package com.sbshop.agent.core.application.sourcing.enrich;

import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.enums.CandidateStatus;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import com.sbshop.agent.core.domain.sourcing.repository.ProductDraftRepository;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DraftPersistTxService {
	private final ProductDraftRepository draftRepository;
	private final SourcingCandidateRepository candidateRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ProductDraft saveAndMarkDrafted(ProductDraft draft, Long candidateId) {
		if (candidateId != null && candidateRepository.claimDraft(candidateId, CandidateStatus.SCORED,
			CandidateStatus.DRAFTED, List.of(CustomsVerdict.PASS, CustomsVerdict.REVIEW)) != 1) {
			throw new IllegalStateException("추천 후보 상태가 변경되었거나 이미 초안이 생성되었습니다: " + candidateId);
		}
		return draftRepository.save(draft);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ProductDraft save(ProductDraft draft) {
		return draftRepository.save(draft);
	}

	@Transactional(readOnly = true)
	public SourcingCandidate requireCandidate(Long candidateId) {
		return candidateRepository.findById(candidateId)
			.orElseThrow(() -> new IllegalArgumentException("후보를 찾을 수 없습니다: " + candidateId));
	}
}
