package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.batch.BarcodeBackfillRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.ProductBarcodeBackfillService;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

class BatchControllerBarcodeBackfillTest {

	private ProcessStatusService processStatusService;
	private ProductBarcodeBackfillService backfillService;
	private BatchController controller;

	@BeforeEach
	void setUp() {
		processStatusService = Mockito.mock(ProcessStatusService.class);
		backfillService = Mockito.mock(ProductBarcodeBackfillService.class);
		controller = new BatchController(
			Mockito.mock(BatchPriceStockService.class), processStatusService,
			Mockito.mock(ActionLogService.class), Mockito.mock(ApplicationEventPublisher.class),
			backfillService);
	}

	@Test
	@DisplayName("바코드 백필 응답은 batchId·count·message 공통 키셋이고 count는 대상 수다")
	void backfillBarcode_hasCommonKeyset() {
		when(backfillService.findTargets(eq(VendorType.VTB), eq(50))).thenReturn(List.of(1L, 2L, 3L));
		when(processStatusService.startBatch(eq(JobType.BACKFILL_BARCODE), any())).thenReturn("batch-9");

		ResponseEntity<Map<String, String>> resp =
			controller.backfillBarcode(new BarcodeBackfillRequest("VTB", 50));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "batch-9")
			.containsEntry("count", "3");
		verify(backfillService).backfillBarcodes(eq("batch-9"), eq(List.of(1L, 2L, 3L)), any());
	}

	@Test
	@DisplayName("소싱처를 비우면 전 소싱처를 대상으로 선정한다")
	void backfillBarcode_withoutVendor_scansAll() {
		when(backfillService.findTargets(eq(null), eq(0))).thenReturn(List.of(7L));
		when(processStatusService.startBatch(eq(JobType.BACKFILL_BARCODE), any())).thenReturn("batch-10");

		controller.backfillBarcode(new BarcodeBackfillRequest(null, 0));

		verify(backfillService).findTargets(null, 0);
	}

	@Test
	@DisplayName("대상이 없으면 배치를 시작하지 않고 빈 batchId를 돌려준다")
	void backfillBarcode_noTargets_returnsEmptyBatchId() {
		when(backfillService.findTargets(any(), eq(0))).thenReturn(List.of());

		ResponseEntity<Map<String, String>> resp =
			controller.backfillBarcode(new BarcodeBackfillRequest("IHB", 0));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "")
			.containsEntry("count", "0");
		verify(processStatusService, never()).startBatch(any(), any());
		verify(backfillService, never()).backfillBarcodes(any(), any(), any());
	}

	@Test
	@DisplayName("알 수 없는 소싱처 코드는 거부한다")
	void backfillBarcode_unknownVendor_isRejected() {
		assertThatThrownBy(() -> controller.backfillBarcode(new BarcodeBackfillRequest("XXX", 0)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
