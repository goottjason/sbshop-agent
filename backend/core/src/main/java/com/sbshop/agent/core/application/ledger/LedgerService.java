package com.sbshop.agent.core.application.ledger;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.ledger.LedgerBudget;
import com.sbshop.agent.core.domain.ledger.LedgerExpense;
import com.sbshop.agent.core.domain.ledger.repository.LedgerBudgetRepository;
import com.sbshop.agent.core.domain.ledger.repository.LedgerExpenseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

	private final LedgerExpenseRepository expenseRepository;
	private final LedgerBudgetRepository budgetRepository;

	@Transactional(readOnly = true)
	public List<LedgerExpense> findExpenses(LocalDate startDate, LocalDate endDate, String adminUser) {
		if (adminUser != null && !adminUser.isBlank() && !"ALL".equalsIgnoreCase(adminUser)) {
			return expenseRepository.findBySpentDateBetweenAndAdminUserAndStatusOrderBySpentDateDescIdDesc(
				startDate, endDate, adminUser, RecordStatus.ACTIVE);
		}
		return expenseRepository.findBySpentDateBetweenAndStatusOrderBySpentDateDescIdDesc(
			startDate, endDate, RecordStatus.ACTIVE);
	}

	@Transactional
	public LedgerExpense createExpense(LocalDate spentDate, BigDecimal amount, String category,
			String paymentSource, String memo, String adminUser) {
		if (spentDate == null) {
			spentDate = LocalDate.now();
		}
		if (amount == null) {
			throw new IllegalArgumentException("지출 금액은 필수입니다.");
		}
		if (category == null || category.isBlank()) {
			category = "기타";
		}
		if (paymentSource == null || paymentSource.isBlank()) {
			paymentSource = "현금";
		}
		if (adminUser == null || adminUser.isBlank()) {
			adminUser = "관리자A";
		}

		LedgerExpense expense = LedgerExpense.builder()
			.spentDate(spentDate)
			.amount(amount)
			.category(category.trim())
			.paymentSource(paymentSource.trim())
			.memo(memo)
			.adminUser(adminUser.trim())
			.build();

		return expenseRepository.save(expense);
	}

	@Transactional
	public LedgerExpense updateExpense(Long id, LocalDate spentDate, BigDecimal amount,
			String category, String paymentSource, String memo, String adminUser) {
		LedgerExpense expense = expenseRepository.findByIdAndStatus(id, RecordStatus.ACTIVE)
			.orElseThrow(() -> new IllegalArgumentException("해당 지출 내역을 찾을 수 없습니다: id=" + id));

		expense.update(spentDate, amount, category, paymentSource, memo, adminUser);
		return expense;
	}

	@Transactional
	public void deleteExpense(Long id) {
		LedgerExpense expense = expenseRepository.findByIdAndStatus(id, RecordStatus.ACTIVE)
			.orElseThrow(() -> new IllegalArgumentException("해당 지출 내역을 찾을 수 없습니다: id=" + id));
		expense.delete();
	}

	@Transactional(readOnly = true)
	public List<String> getDistinctPaymentSources() {
		return expenseRepository.findDistinctPaymentSources(RecordStatus.ACTIVE);
	}

	@Transactional(readOnly = true)
	public List<String> getDistinctCategories() {
		return expenseRepository.findDistinctCategories(RecordStatus.ACTIVE);
	}

	@Transactional(readOnly = true)
	public MonthlySummary getMonthlySummary(String yearMonthStr, String adminUser) {
		YearMonth ym = (yearMonthStr != null && !yearMonthStr.isBlank())
			? YearMonth.parse(yearMonthStr)
			: YearMonth.now();

		LocalDate startDate = ym.atDay(1);
		LocalDate endDate = ym.atEndOfMonth();

		List<LedgerExpense> expenses = findExpenses(startDate, endDate, adminUser);

		BigDecimal totalAmount = BigDecimal.ZERO;
		Map<String, BigDecimal> byCategory = new HashMap<>();
		Map<String, BigDecimal> byPaymentSource = new HashMap<>();
		Map<String, BigDecimal> byAdminUser = new HashMap<>();

		for (LedgerExpense e : expenses) {
			BigDecimal amt = e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO;
			totalAmount = totalAmount.add(amt);

			byCategory.merge(e.getCategory(), amt, BigDecimal::add);
			byPaymentSource.merge(e.getPaymentSource(), amt, BigDecimal::add);
			byAdminUser.merge(e.getAdminUser(), amt, BigDecimal::add);
		}

		String budgetUserKey = (adminUser != null && !adminUser.isBlank() && !"ALL".equalsIgnoreCase(adminUser))
			? adminUser
			: "ALL";

		BigDecimal budgetAmount = budgetRepository.findByYearMonthAndAdminUserAndStatus(
			ym.toString(), budgetUserKey, RecordStatus.ACTIVE)
			.map(LedgerBudget::getTargetAmount)
			.orElse(BigDecimal.ZERO);

		return MonthlySummary.builder()
			.yearMonth(ym.toString())
			.adminUser(budgetUserKey)
			.totalAmount(totalAmount)
			.budgetAmount(budgetAmount)
			.byCategory(byCategory)
			.byPaymentSource(byPaymentSource)
			.byAdminUser(byAdminUser)
			.count(expenses.size())
			.build();
	}

	@Transactional(readOnly = true)
	public LedgerBudget getBudget(String yearMonth, String adminUser) {
		String userKey = (adminUser == null || adminUser.isBlank()) ? "ALL" : adminUser;
		return budgetRepository.findByYearMonthAndAdminUserAndStatus(yearMonth, userKey, RecordStatus.ACTIVE)
			.orElse(null);
	}

	@Transactional
	public LedgerBudget setBudget(String yearMonth, String adminUser, BigDecimal targetAmount) {
		String userKey = (adminUser == null || adminUser.isBlank()) ? "ALL" : adminUser;
		LedgerBudget budget = budgetRepository.findByYearMonthAndAdminUserAndStatus(yearMonth, userKey, RecordStatus.ACTIVE)
			.orElse(null);

		if (budget == null) {
			return budgetRepository.save(LedgerBudget.builder()
				.yearMonth(yearMonth)
				.adminUser(userKey)
				.targetAmount(targetAmount != null ? targetAmount : BigDecimal.ZERO)
				.build());
		}

		budget.updateTargetAmount(targetAmount != null ? targetAmount : BigDecimal.ZERO);
		return budget;
	}

	@Builder
	public record MonthlySummary(
		String yearMonth,
		String adminUser,
		BigDecimal totalAmount,
		BigDecimal budgetAmount,
		Map<String, BigDecimal> byCategory,
		Map<String, BigDecimal> byPaymentSource,
		Map<String, BigDecimal> byAdminUser,
		int count
	) {}
}
