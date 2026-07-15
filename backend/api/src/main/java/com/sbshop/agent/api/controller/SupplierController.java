package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.supplier.CurrencyResponse;
import com.sbshop.agent.api.dto.supplier.SupplierResponse;
import com.sbshop.agent.core.application.supplier.SupplierService;
import com.sbshop.agent.core.application.supplier.dto.CreateCurrencyCommand;
import com.sbshop.agent.core.application.supplier.dto.CreateSupplierCommand;
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

	@GetMapping("/suppliers")
	public ResponseEntity<List<SupplierResponse>> getSuppliers() {
		return ResponseEntity.ok(supplierService.getSuppliers().stream().map(SupplierResponse::from).toList());
	}

	@PostMapping("/suppliers")
	public ResponseEntity<SupplierResponse> createSupplier(@RequestBody
	SupplierRequest request) {
		Supplier supplier = supplierService.createSupplier(
			new CreateSupplierCommand(request.supplierCode(), request.supplierName(), request.currencyCode()));
		return ResponseEntity.ok(SupplierResponse.from(supplier));
	}

	@GetMapping("/currencies")
	public ResponseEntity<List<CurrencyResponse>> getCurrencies() {
		return ResponseEntity.ok(supplierService.getCurrencies().stream().map(CurrencyResponse::from).toList());
	}

	@PostMapping("/currencies")
	public ResponseEntity<CurrencyResponse> createCurrency(@RequestBody
	CurrencyRequest request) {
		Currency currency = supplierService.createCurrency(
			new CreateCurrencyCommand(request.currencyCode(), request.exchangeRate()));
		return ResponseEntity.ok(CurrencyResponse.from(currency));
	}

	public record SupplierRequest(String supplierCode, String supplierName, String currencyCode) {
	}

	public record CurrencyRequest(String currencyCode, BigDecimal exchangeRate) {
	}
}
