package com.sbshop.agent.core.domain.ledger.repository;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.ledger.LedgerCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerCategoryRepository extends JpaRepository<LedgerCategory, Long> {

	List<LedgerCategory> findByStatusOrderByDisplayOrderAscIdAsc(RecordStatus status);

	Optional<LedgerCategory> findByName(String name);

	boolean existsByNameAndStatus(String name, RecordStatus status);
}
