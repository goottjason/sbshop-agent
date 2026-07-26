package com.sbshop.agent.core.domain.sourcing.repository;

import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.enums.DraftStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductDraftRepository extends JpaRepository<ProductDraft, Long> {

	List<ProductDraft> findByDraftStatusIn(Collection<DraftStatus> statuses);

	List<ProductDraft> findByCandidateId(Long candidateId);
}
