package com.sbshop.agent.api.controller;

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
	public ResponseEntity<List<Supplier>> getSuppliers() {
		return ResponseEntity.ok(supplierService.getSuppliers());
	}

	@PostMapping("/suppliers")
	public ResponseEntity<Supplier> createSupplier(@RequestBody
	SupplierRequest request) {
		Supplier supplier = supplierService.createSupplier(
			new CreateSupplierCommand(request.supplierCode(), request.supplierName(), request.currencyCode()));
		return ResponseEntity.ok(supplier);
	}

	@GetMapping("/currencies")
	public ResponseEntity<List<Currency>> getCurrencies() {
		return ResponseEntity.ok(supplierService.getCurrencies());
	}

	@PostMapping("/currencies")
	public ResponseEntity<Currency> createCurrency(@RequestBody
	CurrencyRequest request) {
		Currency currency = supplierService.createCurrency(
			new CreateCurrencyCommand(request.currencyCode(), request.exchangeRate()));
		return ResponseEntity.ok(currency);
	}

	public record SupplierRequest(String supplierCode, String supplierName, String currencyCode) {
	}

	public record CurrencyRequest(String currencyCode, BigDecimal exchangeRate) {
	}
}
