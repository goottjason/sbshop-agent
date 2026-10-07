package com.sbshop.agent.core.domain.sourcing.repository;

import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.enums.DraftStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductDraftRepository extends JpaRepository<ProductDraft, Long> {
	List<ProductDraft> findByDraftStatusIn(Collection<DraftStatus> statuses);

	List<ProductDraft> findByCandidateId(Long candidateId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT d FROM ProductDraft d WHERE d.id = :id")
	Optional<ProductDraft> findForUpdate(@Param("id")
	Long id);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE ProductDraft d SET d.draftStatus = :next WHERE d.id = :id AND d.draftStatus IN :allowed")
	int claimPublishing(@Param("id")
	Long id, @Param("next")
	DraftStatus next,
		@Param("allowed")
		Collection<DraftStatus> allowed);
}
