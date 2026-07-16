package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.controller.SupplierController.CurrencyRequest;
import com.sbshop.agent.api.controller.SupplierController.SupplierRequest;
import com.sbshop.agent.core.application.supplier.SupplierService;
import com.sbshop.agent.core.application.supplier.dto.CreateCurrencyCommand;
import com.sbshop.agent.core.application.supplier.dto.CreateSupplierCommand;
import com.sbshop.agent.core.domain.supplier.Currency;
import com.sbshop.agent.core.domain.supplier.Supplier;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-9 서비스 계층 추출: 통화/공급사 가드 로직(F-SUP-UC-1/2/3)은 SupplierService로 이동했다.
 * 그 동작 보존 증거는 core의 SupplierServiceTest에 이관되어 있다.
 * 이 테스트는 컨트롤러가 요청 record를 커맨드로 매핑해 서비스에 위임만 하는지(얇은 컨트롤러) 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SupplierControllerCurrencyGuardTest {

	@Mock private SupplierService supplierService;
	@Mock private com.sbshop.agent.core.application.actionlog.ActionLogService actionLogService;

	private SupplierController controller() {
		return new SupplierController(supplierService, actionLogService);
	}

	@Test
	@DisplayName("createCurrency → CurrencyRequest를 CreateCurrencyCommand로 매핑해 서비스에 위임")
	void createCurrency_delegatesToService() {
		when(supplierService.createCurrency(any())).thenReturn(new Currency("EUR", new BigDecimal("1500")));
		ArgumentCaptor<CreateCurrencyCommand> captor = ArgumentCaptor.forClass(CreateCurrencyCommand.class);

		controller().createCurrency(new CurrencyRequest("EUR", new BigDecimal("1500")));

		verify(supplierService).createCurrency(captor.capture());
		assertThat(captor.getValue().currencyCode()).isEqualTo("EUR");
		assertThat(captor.getValue().exchangeRate()).isEqualByComparingTo("1500");
	}

	@Test
	@DisplayName("createSupplier → SupplierRequest를 CreateSupplierCommand로 매핑해 서비스에 위임")
	void createSupplier_delegatesToService() {
		when(supplierService.createSupplier(any())).thenReturn(
			new Supplier("SUP01", "테스트공급사", new Currency("USD", new BigDecimal("1400"))));
		ArgumentCaptor<CreateSupplierCommand> captor = ArgumentCaptor.forClass(CreateSupplierCommand.class);

		controller().createSupplier(new SupplierRequest("SUP01", "테스트공급사", "USD"));

		verify(supplierService).createSupplier(captor.capture());
		assertThat(captor.getValue().supplierCode()).isEqualTo("SUP01");
		assertThat(captor.getValue().supplierName()).isEqualTo("테스트공급사");
		assertThat(captor.getValue().currencyCode()).isEqualTo("USD");
	}
}
