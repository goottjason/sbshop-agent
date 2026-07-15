package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.controller.SupplierController.CurrencyRequest;
import com.sbshop.agent.core.domain.supplier.Currency;
import com.sbshop.agent.core.domain.supplier.repository.CurrencyRepository;
import com.sbshop.agent.core.domain.supplier.repository.SupplierRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-SUP-UC-1: POST /currencies가 기존 통화 환율을 무경고로 덮어써 정산/매입원가를 왜곡한다.
 * 정책(사용자 결정): 생성 전용 — 이미 존재하는 통화면 거부(기존 환율 불변), 값 검증 추가(F-SUP-UC-2·3 동반).
 */
@ExtendWith(MockitoExtension.class)
class SupplierControllerCurrencyGuardTest {

	@Mock private SupplierRepository supplierRepository;
	@Mock private CurrencyRepository currencyRepository;

	private SupplierController controller() {
		return new SupplierController(supplierRepository, currencyRepository);
	}

	private CurrencyRequest request(String code, BigDecimal rate) {
		return new CurrencyRequest(code, rate);
	}

	@Test
	@DisplayName("이미 존재하는 통화 → 거부(IllegalStateException), save 호출 안 됨(기존 환율 불변)")
	void existingCurrency_rejected_notOverwritten() {
		when(currencyRepository.existsById("USD")).thenReturn(true);

		assertThatThrownBy(() -> controller().createCurrency(request("USD", new BigDecimal("1400"))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("USD");

		verify(currencyRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("신규 통화 → 정상 생성(save 호출)")
	void newCurrency_created() {
		when(currencyRepository.existsById("EUR")).thenReturn(false);
		ArgumentCaptor<Currency> captor = ArgumentCaptor.forClass(Currency.class);
		when(currencyRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

		controller().createCurrency(request("EUR", new BigDecimal("1500")));

		assertThat(captor.getValue().getCurrencyCode()).isEqualTo("EUR");
		assertThat(captor.getValue().getExchangeRate()).isEqualByComparingTo("1500");
	}

	@Test
	@DisplayName("exchangeRate null → 거부(IllegalArgumentException), save 안 됨 (F-SUP-UC-2)")
	void nullRate_rejected() {
		assertThatThrownBy(() -> controller().createCurrency(request("JPY", null)))
			.isInstanceOf(IllegalArgumentException.class);
		verify(currencyRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("exchangeRate <= 0 → 거부(IllegalArgumentException), save 안 됨 (F-SUP-UC-2)")
	void nonPositiveRate_rejected() {
		assertThatThrownBy(() -> controller().createCurrency(request("JPY", BigDecimal.ZERO)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> controller().createCurrency(request("JPY", new BigDecimal("-1"))))
			.isInstanceOf(IllegalArgumentException.class);
		verify(currencyRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("currencyCode blank → 거부(IllegalArgumentException), save 안 됨 (F-SUP-UC-3)")
	void blankCode_rejected() {
		assertThatThrownBy(() -> controller().createCurrency(request("   ", new BigDecimal("1400"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> controller().createCurrency(request(null, new BigDecimal("1400"))))
			.isInstanceOf(IllegalArgumentException.class);
		verify(currencyRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}
}
