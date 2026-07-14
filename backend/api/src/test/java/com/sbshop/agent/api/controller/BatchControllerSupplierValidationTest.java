package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.api.dto.batch.SupplierBatchRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 1 파생결함: by-supplier 배치에서 supplierCode가 null/blank이면 toUpperCase() NPE로 500이 났다.
 * 사용자 입력 오류이므로 IllegalArgumentException(→400)으로 표면화한다.
 */
@ExtendWith(MockitoExtension.class)
class BatchControllerSupplierValidationTest {

	@Mock private BatchPriceStockService batchPriceStockService;
	@Mock private ProcessStatusService processStatusService;
	@Mock private ActionLogService actionLogService;

	private BatchController controller() {
		return new BatchController(batchPriceStockService, processStatusService, actionLogService);
	}

	private SupplierBatchRequest requestWith(String supplierCode) {
		return new SupplierBatchRequest(supplierCode, null, null, null);
	}

	@Test
	@DisplayName("supplierCode null → IllegalArgumentException(400), NPE(500) 아님")
	void nullSupplierCode_throwsIllegalArgument() {
		assertThatThrownBy(() -> controller().updateBySupplier(requestWith(null)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("supplierCode blank → IllegalArgumentException(400)")
	void blankSupplierCode_throwsIllegalArgument() {
		assertThatThrownBy(() -> controller().updateBySupplier(requestWith("   ")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
