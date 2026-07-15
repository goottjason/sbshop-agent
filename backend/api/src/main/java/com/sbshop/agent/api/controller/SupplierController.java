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
	public ResponseEntity<Supplier> createSupplier(@RequestBody
	SupplierRequest request) {
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
	public ResponseEntity<Currency> createCurrency(@RequestBody
	CurrencyRequest request) {
		// F-SUP-UC-3: 통화 코드 필수 (데이터 오염 예방)
		if (request.currencyCode() == null || request.currencyCode().isBlank()) {
			throw new IllegalArgumentException("통화 코드는 필수입니다");
		}
		// F-SUP-UC-2: 환율은 양수 필수 (정산/매입원가 오염 예방)
		if (request.exchangeRate() == null || request.exchangeRate().signum() <= 0) {
			throw new IllegalArgumentException("환율은 0보다 커야 합니다: " + request.exchangeRate());
		}
		// F-SUP-UC-1: 생성 전용 — 이미 존재하면 거부(기존 환율 불변). 환율 변경은 별도 경로.
		if (currencyRepository.existsById(request.currencyCode())) {
			throw new IllegalStateException("이미 존재하는 통화입니다: " + request.currencyCode());
		}
		Currency currency = new Currency(request.currencyCode(), request.exchangeRate());
		return ResponseEntity.ok(currencyRepository.save(currency));
	}

	public record SupplierRequest(String supplierCode, String supplierName, String currencyCode) {
	}

	public record CurrencyRequest(String currencyCode, BigDecimal exchangeRate) {
	}
}
