package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.controller.SupplierController.CurrencyRequest;
import com.sbshop.agent.api.controller.SupplierController.SupplierRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.supplier.SupplierService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.supplier.Currency;
import com.sbshop.agent.core.domain.supplier.Supplier;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierControllerActionLogTest {

	@Mock
	private SupplierService supplierService;
	@Mock
	private ActionLogService actionLogService;

	private SupplierController controller() {
		return new SupplierController(supplierService, actionLogService);
	}

	@Test
	@DisplayName("F-SUP-3: createSupplier 성공 시 SUPPLIER_CREATE SUCCESS 로그를 남긴다")
	void createSupplier_success_recordsActionLog() {
		when(supplierService.createSupplier(any())).thenReturn(
			new Supplier("SUP01", "테스트공급사", new Currency("USD", new BigDecimal("1400"))));

		controller().createSupplier(new SupplierRequest("SUP01", "테스트공급사", "USD"));

		verify(actionLogService).record(
			eq(ActionLogConstants.SUPPLIER_CREATE), isNull(), eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("F-SUP-3: createSupplier 실패 시 SUPPLIER_CREATE FAILED 로그 후 재던짐")
	void createSupplier_failure_recordsFailedAndRethrows() {
		when(supplierService.createSupplier(any()))
			.thenThrow(new IllegalArgumentException("중복 코드"));

		assertThatThrownBy(() -> controller().createSupplier(new SupplierRequest("SUP01", "x", "USD")))
			.isInstanceOf(IllegalArgumentException.class);

		verify(actionLogService).record(
			eq(ActionLogConstants.SUPPLIER_CREATE), isNull(), eq(ActionStatus.FAILED), any());
	}

	@Test
	@DisplayName("F-SUP-UC-5: createCurrency 성공 시 CURRENCY_CREATE SUCCESS 로그를 남긴다")
	void createCurrency_success_recordsActionLog() {
		when(supplierService.createCurrency(any()))
			.thenReturn(new Currency("EUR", new BigDecimal("1500")));

		controller().createCurrency(new CurrencyRequest("EUR", new BigDecimal("1500")));

		verify(actionLogService).record(
			eq(ActionLogConstants.CURRENCY_CREATE), isNull(), eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("F-SUP-UC-5: createCurrency 실패 시 CURRENCY_CREATE FAILED 로그 후 재던짐")
	void createCurrency_failure_recordsFailedAndRethrows() {
		when(supplierService.createCurrency(any()))
			.thenThrow(new IllegalArgumentException("잘못된 환율"));

		assertThatThrownBy(() -> controller().createCurrency(new CurrencyRequest("EUR", new BigDecimal("1500"))))
			.isInstanceOf(IllegalArgumentException.class);

		verify(actionLogService).record(
			eq(ActionLogConstants.CURRENCY_CREATE), isNull(), eq(ActionStatus.FAILED), any());
	}
}
