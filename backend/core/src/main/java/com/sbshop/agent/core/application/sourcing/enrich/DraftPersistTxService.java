package com.sbshop.agent.core.application.sourcing.enrich;

import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.repository.ProductDraftRepository;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초안 저장 — 초안 1건 단위의 짧은 트랜잭션.
 *
 * <p>{@link DraftEnrichmentUseCase}는 초안마다 상세 크롤·이미지 업로드·LLM 호출을 한다.
 * 전체를 한 트랜잭션으로 묶으면 커넥션을 수 분 점유하고, 마지막 한 건이 터지면 앞서 만든 초안까지
 * 전부 롤백된다(이미 R2에 올라간 이미지는 롤백되지 않아 고아만 남는다).
 */
@Service
@RequiredArgsConstructor
public class DraftPersistTxService {

	private final ProductDraftRepository draftRepository;
	private final SourcingCandidateRepository candidateRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ProductDraft saveAndMarkDrafted(ProductDraft draft, Long candidateId) {
		ProductDraft saved = draftRepository.save(draft);
		if (candidateId != null) {
			candidateRepository.findById(candidateId).ifPresent(c -> {
				c.markDrafted();
				candidateRepository.save(c);
			});
		}
		return saved;
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
