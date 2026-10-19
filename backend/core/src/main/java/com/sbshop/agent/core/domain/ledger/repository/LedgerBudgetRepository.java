package com.sbshop.agent.core.domain.ledger.repository;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.ledger.LedgerBudget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerBudgetRepository extends JpaRepository<LedgerBudget, Long> {

	Optional<LedgerBudget> findByYearMonthAndAdminUserAndStatus(
		String yearMonth, String adminUser, RecordStatus status);

	List<LedgerBudget> findByYearMonthAndStatus(String yearMonth, RecordStatus status);
}
