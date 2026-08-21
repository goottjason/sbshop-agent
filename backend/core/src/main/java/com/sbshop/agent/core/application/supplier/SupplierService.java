package com.sbshop.agent.core.application.supplier;

import com.sbshop.agent.core.application.supplier.dto.CreateCurrencyCommand;
import com.sbshop.agent.core.application.supplier.dto.CreateSupplierCommand;
import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.supplier.Currency;
import com.sbshop.agent.core.domain.supplier.Supplier;
import com.sbshop.agent.core.domain.supplier.repository.CurrencyRepository;
import com.sbshop.agent.core.domain.supplier.repository.SupplierRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierService {

	private final SupplierRepository supplierRepository;
	private final CurrencyRepository currencyRepository;

	public List<Supplier> getSuppliers() {
		return supplierRepository.findByStatusOrderBySupplierCodeAsc(RecordStatus.ACTIVE);
	}

	@Transactional
	public Supplier createSupplier(CreateSupplierCommand command) {
		if (command.supplierCode() == null || command.supplierCode().isBlank()) {
			throw new IllegalArgumentException("공급사 코드는 필수입니다");
		}
		if (command.supplierName() == null || command.supplierName().isBlank()) {
			throw new IllegalArgumentException("공급사명은 필수입니다");
		}
		if (supplierRepository.existsBySupplierCode(command.supplierCode())) {
			throw new IllegalStateException("이미 존재하는 공급사 코드입니다: " + command.supplierCode());
		}
		Currency currency = currencyRepository.findById(command.currencyCode())
			.orElseThrow(() -> new IllegalArgumentException("통화 없음: " + command.currencyCode()));
		Supplier supplier = new Supplier(command.supplierCode(), command.supplierName(), currency);
		return supplierRepository.save(supplier);
	}

	public List<Currency> getCurrencies() {
		return currencyRepository.findAllByOrderByCurrencyCodeAsc();
	}

	@Transactional
	public Currency createCurrency(CreateCurrencyCommand command) {
		if (command.currencyCode() == null || command.currencyCode().isBlank()) {
			throw new IllegalArgumentException("통화 코드는 필수입니다");
		}
		if (command.exchangeRate() == null || command.exchangeRate().signum() <= 0) {
			throw new IllegalArgumentException("환율은 0보다 커야 합니다: " + command.exchangeRate());
		}
		if (currencyRepository.existsById(command.currencyCode())) {
			throw new IllegalStateException("이미 존재하는 통화입니다: " + command.currencyCode());
		}
		Currency currency = new Currency(command.currencyCode(), command.exchangeRate());
		return currencyRepository.save(currency);
	}
}
