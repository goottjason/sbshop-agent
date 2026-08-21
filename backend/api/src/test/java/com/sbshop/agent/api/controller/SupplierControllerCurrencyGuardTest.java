package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.controller.SupplierController.CurrencyRequest;
import com.sbshop.agent.api.controller.SupplierController.SupplierRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
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

@ExtendWith(MockitoExtension.class)
class SupplierControllerCurrencyGuardTest {

	@Mock
	private SupplierService supplierService;
	@Mock
	private ActionLogService actionLogService;

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
