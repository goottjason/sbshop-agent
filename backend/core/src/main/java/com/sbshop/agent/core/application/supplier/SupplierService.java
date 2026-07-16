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
		// F-SUP-2: 소프트삭제(ARCHIVED/DELETED)를 제외하고 ACTIVE만 반환한다.
		// F-SUP-4: supplierCode 오름차순으로 정렬해 반환(응답 형태 유지, 정렬만 추가).
		return supplierRepository.findByStatusOrderBySupplierCodeAsc(RecordStatus.ACTIVE);
	}

	@Transactional
	public Supplier createSupplier(CreateSupplierCommand command) {
		// F-SUP-CS-1: supplierCode/supplierName 필수 (데이터 오염 예방)
		if (command.supplierCode() == null || command.supplierCode().isBlank()) {
			throw new IllegalArgumentException("공급사 코드는 필수입니다");
		}
		if (command.supplierName() == null || command.supplierName().isBlank()) {
			throw new IllegalArgumentException("공급사명은 필수입니다");
		}
		// F-SUP-CS-2: 중복 코드는 DB unique 예외 대신 사전검증으로 명확히 거부(F-SUP-UC-1 통화 중복거부와 대칭).
		if (supplierRepository.existsBySupplierCode(command.supplierCode())) {
			throw new IllegalStateException("이미 존재하는 공급사 코드입니다: " + command.supplierCode());
		}
		Currency currency = currencyRepository.findById(command.currencyCode())
			.orElseThrow(() -> new IllegalArgumentException("통화 없음: " + command.currencyCode()));
		Supplier supplier = new Supplier(command.supplierCode(), command.supplierName(), currency);
		return supplierRepository.save(supplier);
	}

	public List<Currency> getCurrencies() {
		// F-SUP-LC-2: currencyCode 오름차순으로 정렬해 반환(응답 형태 유지, 정렬만 추가).
		return currencyRepository.findAllByOrderByCurrencyCodeAsc();
	}

	@Transactional
	public Currency createCurrency(CreateCurrencyCommand command) {
		// F-SUP-UC-3: 통화 코드 필수 (데이터 오염 예방)
		if (command.currencyCode() == null || command.currencyCode().isBlank()) {
			throw new IllegalArgumentException("통화 코드는 필수입니다");
		}
		// F-SUP-UC-2: 환율은 양수 필수 (정산/매입원가 오염 예방)
		if (command.exchangeRate() == null || command.exchangeRate().signum() <= 0) {
			throw new IllegalArgumentException("환율은 0보다 커야 합니다: " + command.exchangeRate());
		}
		// F-SUP-UC-1: 생성 전용 — 이미 존재하면 거부(기존 환율 불변). 환율 변경은 별도 경로.
		if (currencyRepository.existsById(command.currencyCode())) {
			throw new IllegalStateException("이미 존재하는 통화입니다: " + command.currencyCode());
		}
		Currency currency = new Currency(command.currencyCode(), command.exchangeRate());
		return currencyRepository.save(currency);
	}
}
