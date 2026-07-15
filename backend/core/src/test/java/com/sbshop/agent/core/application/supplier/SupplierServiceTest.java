package com.sbshop.agent.core.application.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.supplier.dto.CreateCurrencyCommand;
import com.sbshop.agent.core.application.supplier.dto.CreateSupplierCommand;
import com.sbshop.agent.core.domain.supplier.Currency;
import com.sbshop.agent.core.domain.supplier.Supplier;
import com.sbshop.agent.core.domain.supplier.repository.CurrencyRepository;
import com.sbshop.agent.core.domain.supplier.repository.SupplierRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-9 서비스 계층 추출: SupplierController의 통화/공급사 로직을 SupplierService로 이동.
 * F-SUP-UC-1/2/3 가드(중복 통화 거부·환율/코드 검증)는 SupplierControllerCurrencyGuardTest에서
 * 이관된 동작 보존 증거다. 컨트롤러 대신 서비스를 직접 대상으로 동일 assertion을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

	@Mock private SupplierRepository supplierRepository;
	@Mock private CurrencyRepository currencyRepository;

	private SupplierService service() {
		return new SupplierService(supplierRepository, currencyRepository);
	}

	private CreateCurrencyCommand currencyCommand(String code, BigDecimal rate) {
		return new CreateCurrencyCommand(code, rate);
	}

	@Test
	@DisplayName("이미 존재하는 통화 → 거부(IllegalStateException), save 호출 안 됨(기존 환율 불변)")
	void existingCurrency_rejected_notOverwritten() {
		when(currencyRepository.existsById("USD")).thenReturn(true);

		assertThatThrownBy(() -> service().createCurrency(currencyCommand("USD", new BigDecimal("1400"))))
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

		service().createCurrency(currencyCommand("EUR", new BigDecimal("1500")));

		assertThat(captor.getValue().getCurrencyCode()).isEqualTo("EUR");
		assertThat(captor.getValue().getExchangeRate()).isEqualByComparingTo("1500");
	}

	@Test
	@DisplayName("exchangeRate null → 거부(IllegalArgumentException), save 안 됨 (F-SUP-UC-2)")
	void nullRate_rejected() {
		assertThatThrownBy(() -> service().createCurrency(currencyCommand("JPY", null)))
			.isInstanceOf(IllegalArgumentException.class);
		verify(currencyRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("exchangeRate <= 0 → 거부(IllegalArgumentException), save 안 됨 (F-SUP-UC-2)")
	void nonPositiveRate_rejected() {
		assertThatThrownBy(() -> service().createCurrency(currencyCommand("JPY", BigDecimal.ZERO)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service().createCurrency(currencyCommand("JPY", new BigDecimal("-1"))))
			.isInstanceOf(IllegalArgumentException.class);
		verify(currencyRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("currencyCode blank → 거부(IllegalArgumentException), save 안 됨 (F-SUP-UC-3)")
	void blankCode_rejected() {
		assertThatThrownBy(() -> service().createCurrency(currencyCommand("   ", new BigDecimal("1400"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service().createCurrency(currencyCommand(null, new BigDecimal("1400"))))
			.isInstanceOf(IllegalArgumentException.class);
		verify(currencyRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("공급사 생성 → 통화 조회 후 저장(기존 동작 보존)")
	void createSupplier_resolvesCurrency_andSaves() {
		Currency usd = new Currency("USD", new BigDecimal("1400"));
		when(currencyRepository.findById("USD")).thenReturn(Optional.of(usd));
		ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
		when(supplierRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

		service().createSupplier(new CreateSupplierCommand("SUP01", "테스트공급사", "USD"));

		assertThat(captor.getValue().getSupplierCode()).isEqualTo("SUP01");
		assertThat(captor.getValue().getSupplierName()).isEqualTo("테스트공급사");
		assertThat(captor.getValue().getCurrency()).isSameAs(usd);
	}

	@Test
	@DisplayName("공급사 생성 시 통화 없음 → IllegalArgumentException(기존 동작 보존)")
	void createSupplier_unknownCurrency_rejected() {
		when(currencyRepository.findById("ZZZ")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().createSupplier(new CreateSupplierCommand("SUP01", "이름", "ZZZ")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ZZZ");
		verify(supplierRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("supplierCode blank → 거부(IllegalArgumentException), 통화 조회/save 안 됨 (F-SUP-CS-1)")
	void blankSupplierCode_rejected() {
		assertThatThrownBy(() -> service().createSupplier(new CreateSupplierCommand("   ", "이름", "USD")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service().createSupplier(new CreateSupplierCommand(null, "이름", "USD")))
			.isInstanceOf(IllegalArgumentException.class);
		verify(currencyRepository, never()).findById(org.mockito.ArgumentMatchers.any());
		verify(supplierRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("supplierName blank → 거부(IllegalArgumentException), 통화 조회/save 안 됨 (F-SUP-CS-1)")
	void blankSupplierName_rejected() {
		assertThatThrownBy(() -> service().createSupplier(new CreateSupplierCommand("SUP01", "   ", "USD")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service().createSupplier(new CreateSupplierCommand("SUP01", null, "USD")))
			.isInstanceOf(IllegalArgumentException.class);
		verify(currencyRepository, never()).findById(org.mockito.ArgumentMatchers.any());
		verify(supplierRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("중복 supplierCode → 사전검증으로 거부(IllegalStateException), save 안 됨 (F-SUP-CS-2)")
	void duplicateSupplierCode_rejected() {
		when(supplierRepository.existsBySupplierCode("SUP01")).thenReturn(true);

		assertThatThrownBy(() -> service().createSupplier(new CreateSupplierCommand("SUP01", "이름", "USD")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("SUP01");
		verify(supplierRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("공급사 목록 조회는 ACTIVE만 반환한다 — findByStatus(ACTIVE) 위임 (F-SUP-2)")
	void getSuppliers_returnsOnlyActive() {
		Supplier active = new Supplier("SUP01", "활성공급사",
			new Currency("USD", new BigDecimal("1400")));
		when(supplierRepository.findByStatus(
			com.sbshop.agent.core.domain.common.RecordStatus.ACTIVE))
			.thenReturn(java.util.List.of(active));

		java.util.List<Supplier> result = service().getSuppliers();

		assertThat(result).containsExactly(active);
		verify(supplierRepository, never()).findAll();
	}
}
