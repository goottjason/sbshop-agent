package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.domain.supplier.Currency;
import com.sbshop.agent.core.domain.supplier.Supplier;
import com.sbshop.agent.core.domain.supplier.repository.CurrencyRepository;
import com.sbshop.agent.core.domain.supplier.repository.SupplierRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

	private final SupplierRepository supplierRepository;
	private final CurrencyRepository currencyRepository;

	@GetMapping("/suppliers")
	public ResponseEntity<List<Supplier>> getSuppliers() {
		return ResponseEntity.ok(supplierRepository.findAll());
	}

	@PostMapping("/suppliers")
	public ResponseEntity<Supplier> createSupplier(@RequestBody SupplierRequest request) {
		Currency currency = currencyRepository.findById(request.currencyCode())
				.orElseThrow(() -> new IllegalArgumentException("통화 없음: " + request.currencyCode()));
		Supplier supplier = new Supplier(request.supplierCode(), request.supplierName(), currency);
		return ResponseEntity.ok(supplierRepository.save(supplier));
	}

	@GetMapping("/currencies")
	public ResponseEntity<List<Currency>> getCurrencies() {
		return ResponseEntity.ok(currencyRepository.findAll());
	}

	@PostMapping("/currencies")
	public ResponseEntity<Currency> createCurrency(@RequestBody CurrencyRequest request) {
		Currency currency = new Currency(request.currencyCode(), request.exchangeRate());
		return ResponseEntity.ok(currencyRepository.save(currency));
	}

	public record SupplierRequest(String supplierCode, String supplierName, String currencyCode) {
	}

	public record CurrencyRequest(String currencyCode, BigDecimal exchangeRate) {
	}
}
