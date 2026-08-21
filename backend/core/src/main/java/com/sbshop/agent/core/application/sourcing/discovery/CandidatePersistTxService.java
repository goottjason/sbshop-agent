package com.sbshop.agent.core.application.sourcing.discovery;

import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CandidatePersistTxService {
	private final SourcingCandidateRepository repository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SourcingCandidate save(SourcingCandidate candidate) {
		return repository.save(candidate);
	}
}
