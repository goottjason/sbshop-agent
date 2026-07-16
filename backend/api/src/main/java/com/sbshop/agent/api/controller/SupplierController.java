package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.supplier.CurrencyResponse;
import com.sbshop.agent.api.dto.supplier.SupplierResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.supplier.SupplierService;
import com.sbshop.agent.core.application.supplier.dto.CreateCurrencyCommand;
import com.sbshop.agent.core.application.supplier.dto.CreateSupplierCommand;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.supplier.Currency;
import com.sbshop.agent.core.domain.supplier.Supplier;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SupplierController {

	private final SupplierService supplierService;
	// R2 관측성: 공급사/통화 생성 활동로그 기록 서비스 (F-SUP-3·F-SUP-UC-5)
	private final ActionLogService actionLogService;

	@GetMapping("/suppliers")
	public ResponseEntity<List<SupplierResponse>> getSuppliers() {
		return ResponseEntity.ok(supplierService.getSuppliers().stream().map(SupplierResponse::from).toList());
	}

	@PostMapping("/suppliers")
	public ResponseEntity<SupplierResponse> createSupplier(@RequestBody
	SupplierRequest request) {
		// F-SUP-3: 공급사 신규 등록 결과 기록(공급사는 마켓 무관 → marketType=null).
		try {
			Supplier supplier = supplierService.createSupplier(
				new CreateSupplierCommand(request.supplierCode(), request.supplierName(), request.currencyCode()));
			actionLogService.record(ActionLogConstants.SUPPLIER_CREATE, null,
				ActionStatus.SUCCESS, "공급사 등록 성공 (" + request.supplierCode() + ")");
			return ResponseEntity.ok(SupplierResponse.from(supplier));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.SUPPLIER_CREATE, null,
				ActionStatus.FAILED, "공급사 등록 실패 (" + request.supplierCode() + "): " + e.getMessage());
			throw e;
		}
	}

	@GetMapping("/currencies")
	public ResponseEntity<List<CurrencyResponse>> getCurrencies() {
		return ResponseEntity.ok(supplierService.getCurrencies().stream().map(CurrencyResponse::from).toList());
	}

	@PostMapping("/currencies")
	public ResponseEntity<CurrencyResponse> createCurrency(@RequestBody
	CurrencyRequest request) {
		// F-SUP-UC-5: 통화(환율) 신규 등록 결과 기록(통화는 마켓 무관 → marketType=null).
		try {
			Currency currency = supplierService.createCurrency(
				new CreateCurrencyCommand(request.currencyCode(), request.exchangeRate()));
			actionLogService.record(ActionLogConstants.CURRENCY_CREATE, null,
				ActionStatus.SUCCESS, "통화 등록 성공 (" + request.currencyCode() + ")");
			return ResponseEntity.ok(CurrencyResponse.from(currency));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.CURRENCY_CREATE, null,
				ActionStatus.FAILED, "통화 등록 실패 (" + request.currencyCode() + "): " + e.getMessage());
			throw e;
		}
	}

	public record SupplierRequest(String supplierCode, String supplierName, String currencyCode) {
	}

	public record CurrencyRequest(String currencyCode, BigDecimal exchangeRate) {
	}
}
