package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.batch.CrawlAndUpdateRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateAllRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateRequest;
import com.sbshop.agent.api.dto.batch.SupplierBatchRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.ProductBarcodeBackfillService;
import com.sbshop.agent.core.application.product.ProductBrandBackfillService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

class BatchControllerTriggerCharacterizationTest {

	private BatchPriceStockService batchPriceStockService;
	private ProcessStatusService processStatusService;
	private ActionLogService actionLogService;
	private BatchController controller;

	@BeforeEach
	void setUp() {
		batchPriceStockService = Mockito.mock(BatchPriceStockService.class);
		processStatusService = Mockito.mock(ProcessStatusService.class);
		actionLogService = Mockito.mock(ActionLogService.class);
		controller = new BatchController(batchPriceStockService, processStatusService, actionLogService,
			Mockito.mock(ApplicationEventPublisher.class),
			Mockito.mock(ProductBarcodeBackfillService.class), Mockito.mock(ProductBrandBackfillService.class));
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
	@DisplayName("crawl-and-update: CRAWL_AND_UPDATE_PRICE_STOCK jobType + STARTED 로그 + {batchId, count, message}")
	void crawlAndUpdate_characterization() {
		var response = controller.crawlAndUpdate(new CrawlAndUpdateRequest(List.of(10L, 20L), null, null, null));
		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody()).containsEntry("code", "SOURCE_REVIEW_REQUIRED")
			.containsEntry("reviewPath", "/api/v1/products/source-refresh/collections");
		Mockito.verifyNoInteractions(batchPriceStockService, processStatusService);
	}

	@Test
	@DisplayName("manual-update-price-stock: MANUAL_UPDATE_PRICE_STOCK jobType + STARTED 로그 + {batchId, count, message}")
	void manualUpdate_characterization() {
		when(processStatusService.startBatch(eq(JobType.MANUAL_UPDATE_PRICE_STOCK), any()))
			.thenReturn("batch-2");
		List<PriceStockItem> items =
			List.of(new PriceStockItem(5L, new BigDecimal("100"), 3));
		ManualUpdateRequest req = new ManualUpdateRequest(items);

		ResponseEntity<Map<String, String>> resp = controller.manualUpdate(req);

		verify(processStatusService).startBatch(JobType.MANUAL_UPDATE_PRICE_STOCK, List.of("5"));
		verify(actionLogService).record(eq(ActionLogConstants.BATCH_MANUAL_UPDATE), isNull(),
			eq(ActionStatus.STARTED), eq("수동 일괄 업데이트 시작 (batchId=batch-2, 1건)"));
		verify(batchPriceStockService).manualUpdatePriceStock("batch-2", items);
		assertThat(resp.getBody())
			.containsEntry("batchId", "batch-2")
			.containsEntry("message", "수동 일괄 업데이트가 시작되었습니다.");
	}

	@Test
	@DisplayName("manual-update-all: MANUAL_UPDATE_ALL_FIELDS jobType + STARTED 로그 + {batchId, count, message}")
	void manualUpdateAll_characterization() {
		when(processStatusService.startBatch(eq(JobType.MANUAL_UPDATE_ALL_FIELDS), any()))
			.thenReturn("batch-3");
		ManualUpdateAllRequest req = new ManualUpdateAllRequest(
			List.of(7L, 8L), List.of(emptyCommand(), emptyCommand()));

		ResponseEntity<Map<String, String>> resp = controller.manualUpdateAll(req);

		verify(processStatusService).startBatch(JobType.MANUAL_UPDATE_ALL_FIELDS, List.of("7", "8"));
		verify(actionLogService).record(eq(ActionLogConstants.BATCH_MANUAL_UPDATE_ALL), isNull(),
			eq(ActionStatus.STARTED), eq("전체필드 일괄 업데이트 시작 (batchId=batch-3, 2건)"));
		assertThat(resp.getBody())
			.containsEntry("batchId", "batch-3")
			.containsEntry("message", "전체 필드 일괄 업데이트가 시작되었습니다.");
	}

	@Test
	@DisplayName("by-supplier 정상: {batchId, count, message} 동일 키셋 (message 포함)")
	void updateBySupplier_characterization() {
		var response = controller.updateBySupplier(new SupplierBatchRequest("IHB", null, null, null));
		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody()).containsEntry("code", "SOURCE_REVIEW_REQUIRED")
			.containsEntry("reviewPath", "/api/v1/products/source-refresh/collections");
		Mockito.verifyNoInteractions(batchPriceStockService, processStatusService);
	}

	@Test
	@DisplayName("by-supplier 0건: {batchId, count, message} 동일 키셋 (batchId=\"\", count=\"0\")")
	void updateBySupplier_emptyProducts_characterization() {
		var response = controller.updateBySupplier(new SupplierBatchRequest("IHB", null, null, null));
		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody()).containsEntry("code", "SOURCE_REVIEW_REQUIRED")
			.containsEntry("reviewPath", "/api/v1/products/source-refresh/collections");
		Mockito.verifyNoInteractions(batchPriceStockService, processStatusService);
	}
}
