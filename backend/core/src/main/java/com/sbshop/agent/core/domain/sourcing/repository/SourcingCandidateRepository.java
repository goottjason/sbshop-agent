package com.sbshop.agent.core.domain.sourcing.repository;

import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.enums.CandidateStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourcingCandidateRepository extends JpaRepository<SourcingCandidate, Long> {

	Optional<SourcingCandidate> findByVendorAndExternalId(VendorType vendor, String externalId);

	List<SourcingCandidate> findByVendorAndExternalIdIn(VendorType vendor, Collection<String> externalIds);

	/** 추천 목록 — 점수 내림차순. 노출 대상은 SCORED뿐이다. */
	@Query("""
		SELECT c FROM SourcingCandidate c
		WHERE c.candidateStatus = :status
		ORDER BY c.totalScore DESC NULLS LAST, c.id ASC
		""")
	List<SourcingCandidate> findTopScored(@Param("status")
	CandidateStatus status, Pageable pageable);

	List<SourcingCandidate> findByCandidateStatusIn(Collection<CandidateStatus> statuses);

	/** 쿨다운이 끝난 거절 후보 — 다시 추천 대상으로 되살릴 수 있다. */
	List<SourcingCandidate> findByCandidateStatusAndRejectedAtBefore(
		CandidateStatus status, LocalDateTime before);

	long countByCandidateStatus(CandidateStatus status);
}
