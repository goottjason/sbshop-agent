package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.batch.ManualUpdateAllRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.ProductBarcodeBackfillService;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class BatchControllerManualUpdateAllValidationTest {

	@Mock
	private BatchPriceStockService batchPriceStockService;
	@Mock
	private ProcessStatusService processStatusService;
	@Mock
	private ActionLogService actionLogService;

	private BatchController controller() {
		return new BatchController(batchPriceStockService, processStatusService, actionLogService,
			Mockito.mock(ApplicationEventPublisher.class),
			Mockito.mock(ProductBarcodeBackfillService.class));
	}

	private ProductUpdateCommand emptyCommand() {
		return new ProductUpdateCommand(
			null, null, null, null, null,
			null, null, null, null, null, null, null,
			null, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null, null, null, null);
	}

	@Test
	@DisplayName("commands가 productIds보다 짧으면 IllegalArgumentException(400)")
	void lengthMismatch_throwsIllegalArgument() {
		ManualUpdateAllRequest req = new ManualUpdateAllRequest(
			List.of(1L, 2L, 3L), List.of(emptyCommand()));

		assertThatThrownBy(() -> controller().manualUpdateAll(req))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("길이가 일치하면 검증을 통과한다")
	void matchingLength_passesValidation() {
		when(processStatusService.startBatch(any(), any())).thenReturn("batch-1");
		ManualUpdateAllRequest req = new ManualUpdateAllRequest(
			List.of(1L), List.of(emptyCommand()));

		assertThatCode(() -> controller().manualUpdateAll(req))
			.doesNotThrowAnyException();
	}
}
