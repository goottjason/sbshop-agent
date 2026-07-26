package com.sbshop.agent.core.application.sourcing.discovery;

import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후보 1건 저장 — 후보 단위 짧은 트랜잭션.
 *
 * <p>{@link CandidateEnrichmentPipeline}은 후보마다 상세 크롤(브라우저 렌더)과 외부 API를 호출한다.
 * 전체를 한 트랜잭션으로 묶으면 커넥션을 수 분간 점유하고, 중간에 하나 터지면 이미 채점한 후보까지
 * 전부 롤백된다. 후보마다 독립 커밋해 부분 진행을 보존한다
 * (기존 {@code CustomsBatchProcessor}·{@code MarketRegistrationTxService}와 같은 규율).
 */
@Service
@RequiredArgsConstructor
public class CandidatePersistTxService {

	private final SourcingCandidateRepository repository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SourcingCandidate save(SourcingCandidate candidate) {
		return repository.save(candidate);
	}
}
