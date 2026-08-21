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

	@Query("""
		SELECT c FROM SourcingCandidate c
		WHERE c.candidateStatus = :status
		ORDER BY c.totalScore DESC NULLS LAST, c.id ASC
		""")
	List<SourcingCandidate> findTopScored(@Param("status")
	CandidateStatus status, Pageable pageable);

	List<SourcingCandidate> findByCandidateStatusIn(Collection<CandidateStatus> statuses);

	List<SourcingCandidate> findByCandidateStatusAndRejectedAtBefore(
		CandidateStatus status, LocalDateTime before);

	long countByCandidateStatus(CandidateStatus status);
}
