package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.ledger.LedgerService;
import com.sbshop.agent.core.domain.ledger.LedgerBudget;
import com.sbshop.agent.core.domain.ledger.LedgerExpense;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LedgerController {

	private final LedgerService ledgerService;

	@GetMapping("/expenses")
	public ResponseEntity<List<ExpenseResponse>> getExpenses(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(required = false) String adminUser) {
		if (startDate == null) {
			startDate = LocalDate.now().withDayOfMonth(1);
		}
		if (endDate == null) {
			endDate = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
		}

		List<ExpenseResponse> list = ledgerService.findExpenses(startDate, endDate, adminUser).stream()
			.map(ExpenseResponse::from)
			.toList();
		return ResponseEntity.ok(list);
	}

	@PostMapping("/expenses")
	public ResponseEntity<ExpenseResponse> createExpense(@RequestBody ExpenseCreateRequest request) {
		LedgerExpense created = ledgerService.createExpense(
			request.spentDate(),
			request.amount(),
			request.category(),
			request.paymentSource(),
			request.memo(),
			request.adminUser()
		);
		return ResponseEntity.ok(ExpenseResponse.from(created));
	}

	@PutMapping("/expenses/{id}")
	public ResponseEntity<ExpenseResponse> updateExpense(
			@PathVariable Long id,
			@RequestBody ExpenseUpdateRequest request) {
		LedgerExpense updated = ledgerService.updateExpense(
			id,
			request.spentDate(),
			request.amount(),
			request.category(),
			request.paymentSource(),
			request.memo(),
			request.adminUser()
		);
		return ResponseEntity.ok(ExpenseResponse.from(updated));
	}

	@DeleteMapping("/expenses/{id}")
	public ResponseEntity<Void> deleteExpense(@PathVariable Long id) {
		ledgerService.deleteExpense(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/summary")
	public ResponseEntity<LedgerService.MonthlySummary> getSummary(
			@RequestParam(required = false) String yearMonth,
			@RequestParam(required = false) String adminUser) {
		return ResponseEntity.ok(ledgerService.getMonthlySummary(yearMonth, adminUser));
	}

	@GetMapping("/budget")
	public ResponseEntity<BudgetResponse> getBudget(
			@RequestParam String yearMonth,
			@RequestParam(required = false, defaultValue = "ALL") String adminUser) {
		LedgerBudget budget = ledgerService.getBudget(yearMonth, adminUser);
		if (budget == null) {
			return ResponseEntity.ok(new BudgetResponse(yearMonth, adminUser, BigDecimal.ZERO));
		}
		return ResponseEntity.ok(new BudgetResponse(budget.getYearMonth(), budget.getAdminUser(), budget.getTargetAmount()));
	}

	@PostMapping("/budget")
	public ResponseEntity<BudgetResponse> setBudget(@RequestBody BudgetSetRequest request) {
		LedgerBudget budget = ledgerService.setBudget(
			request.yearMonth(),
			request.adminUser(),
			request.targetAmount()
		);
		return ResponseEntity.ok(new BudgetResponse(budget.getYearMonth(), budget.getAdminUser(), budget.getTargetAmount()));
	}

	@GetMapping("/payment-sources")
	public ResponseEntity<List<String>> getPaymentSources() {
		return ResponseEntity.ok(ledgerService.getDistinctPaymentSources());
	}

	@GetMapping("/categories")
	public ResponseEntity<List<String>> getCategories() {
		return ResponseEntity.ok(ledgerService.getDistinctCategories());
	}

	public record ExpenseCreateRequest(
		LocalDate spentDate,
		BigDecimal amount,
		String category,
		String paymentSource,
		String memo,
		String adminUser
	) {}

	public record ExpenseUpdateRequest(
		LocalDate spentDate,
		BigDecimal amount,
		String category,
		String paymentSource,
		String memo,
		String adminUser
	) {}

	public record ExpenseResponse(
		Long id,
		LocalDate spentDate,
		BigDecimal amount,
		String category,
		String paymentSource,
		String memo,
		String adminUser,
		LocalDateTime createdAt
	) {
		public static ExpenseResponse from(LedgerExpense e) {
			return new ExpenseResponse(
				e.getId(),
				e.getSpentDate(),
				e.getAmount(),
				e.getCategory(),
				e.getPaymentSource(),
				e.getMemo(),
				e.getAdminUser(),
				e.getCreatedAt()
			);
		}
	}

	public record BudgetSetRequest(
		String yearMonth,
		String adminUser,
		BigDecimal targetAmount
	) {}

	public record BudgetResponse(
		String yearMonth,
		String adminUser,
		BigDecimal targetAmount
	) {}
}
