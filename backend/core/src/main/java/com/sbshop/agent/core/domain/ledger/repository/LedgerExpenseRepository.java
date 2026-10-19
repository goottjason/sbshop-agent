package com.sbshop.agent.core.domain.ledger.repository;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.ledger.LedgerExpense;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerExpenseRepository extends JpaRepository<LedgerExpense, Long> {

	Optional<LedgerExpense> findByIdAndStatus(Long id, RecordStatus status);

	List<LedgerExpense> findBySpentDateBetweenAndStatusOrderBySpentDateDescIdDesc(
		LocalDate startDate, LocalDate endDate, RecordStatus status);

	List<LedgerExpense> findBySpentDateBetweenAndAdminUserAndStatusOrderBySpentDateDescIdDesc(
		LocalDate startDate, LocalDate endDate, String adminUser, RecordStatus status);

	@Query("SELECT DISTINCT e.paymentSource FROM LedgerExpense e WHERE e.status = :status ORDER BY e.paymentSource ASC")
	List<String> findDistinctPaymentSources(@Param("status") RecordStatus status);

	@Query("SELECT DISTINCT e.category FROM LedgerExpense e WHERE e.status = :status ORDER BY e.category ASC")
	List<String> findDistinctCategories(@Param("status") RecordStatus status);
}
