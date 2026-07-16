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
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

/**
 * SP-11 (F-BATCH-3 / F-BATCH-B3): 4개 트리거 엔드포인트가 공통 골격
 * (startBatch → ActionLog STARTED 기록 → 서비스 호출 → 응답 조립)을 반복한다.
 * 이 특성화 테스트는 각 엔드포인트의 jobType·ActionLog 상수·STARTED 상태·로그 메시지·응답 body를
 * 고정하여, 골격 통합 리팩토링이 동작을 바꾸지 않았음을 증명하는 안전망이다.
 */
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
		controller = new BatchController(batchPriceStockService, processStatusService, actionLogService);
	}

	private ProductUpdateCommand emptyCommand() {
		return new ProductUpdateCommand(
			null, null, null, null, null,
			null, null, null, null, null,
			null, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null, null, null, null);
	}

	@Test
	@DisplayName("crawl-and-update: CRAWL_AND_UPDATE_PRICE_STOCK jobType + STARTED 로그 + {batchId, message}")
	void crawlAndUpdate_characterization() {
		when(processStatusService.startBatch(eq(JobType.CRAWL_AND_UPDATE_PRICE_STOCK), any()))
			.thenReturn("batch-1");
		CrawlAndUpdateRequest req = new CrawlAndUpdateRequest(List.of(10L, 20L), null, null, null);

		ResponseEntity<Map<String, String>> resp = controller.crawlAndUpdate(req);

		verify(processStatusService).startBatch(JobType.CRAWL_AND_UPDATE_PRICE_STOCK, List.of("10", "20"));
		verify(actionLogService).record(eq(ActionLogConstants.BATCH_CRAWL_UPDATE), isNull(),
			eq(ActionStatus.STARTED), eq("배치 크롤 업데이트 시작 (batchId=batch-1, 2건)"));
		assertThat(resp.getBody())
			.containsEntry("batchId", "batch-1")
			.containsEntry("message", "크롤 기반 일괄 업데이트가 시작되었습니다.");
	}

	@Test
	@DisplayName("manual-update-price-stock: MANUAL_UPDATE_PRICE_STOCK jobType + STARTED 로그 + {batchId, message}")
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
	@DisplayName("manual-update-all: MANUAL_UPDATE_ALL_FIELDS jobType + STARTED 로그 + {batchId, message}")
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

	// F-BATCH-B2: by-supplier의 정상 케이스와 0건 케이스가 서로 다른 응답 키셋을 가졌다
	// (정상={batchId,count}·message 없음 / 0건={message}·batchId 없음). 클라이언트가 두 형태를
	// 분기해야 하는 비대칭을 제거하기 위해, 두 경로가 동일한 키셋 {batchId, count, message}를
	// 갖도록 통일한다. Map.of는 null을 못 담으므로 0건은 batchId=""(빈문자열)·count="0"으로 채운다.
	// 프론트(BatchUpdatePage)는 `if (data.batchId)`로 분기하므로 ""는 falsy → 기존 0건 처리와 동일.
	@Test
	@DisplayName("by-supplier 정상: {batchId, count, message} 동일 키셋 (message 포함)")
	void updateBySupplier_characterization() {
		when(batchPriceStockService.getProductIdsByVendor(VendorType.IHB))
			.thenReturn(List.of(1L, 2L, 3L));
		when(processStatusService.startBatch(eq(JobType.CRAWL_AND_UPDATE_PRICE_STOCK), any()))
			.thenReturn("batch-4");
		SupplierBatchRequest req = new SupplierBatchRequest("ihb", null, null, null);

		ResponseEntity<Map<String, String>> resp = controller.updateBySupplier(req);

		verify(processStatusService).startBatch(
			JobType.CRAWL_AND_UPDATE_PRICE_STOCK, List.of("1", "2", "3"));
		verify(actionLogService).record(eq(ActionLogConstants.BATCH_BY_SUPPLIER), isNull(),
			eq(ActionStatus.STARTED),
			eq("소싱업체별 배치 시작 (IHB, batchId=batch-4, 3건)"));
		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "batch-4")
			.containsEntry("count", "3")
			.containsEntry("message", "소싱업체별 일괄 업데이트가 시작되었습니다.");
	}

	@Test
	@DisplayName("by-supplier 0건: {batchId, count, message} 동일 키셋 (batchId=\"\", count=\"0\")")
	void updateBySupplier_emptyProducts_characterization() {
		when(batchPriceStockService.getProductIdsByVendor(VendorType.IHB)).thenReturn(List.of());
		SupplierBatchRequest req = new SupplierBatchRequest("ihb", null, null, null);

		ResponseEntity<Map<String, String>> resp = controller.updateBySupplier(req);

		Mockito.verifyNoInteractions(processStatusService);
		Mockito.verifyNoInteractions(actionLogService);
		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "")
			.containsEntry("count", "0")
			.containsEntry("message", "해당 소싱업체의 상품이 없습니다.");
	}
}
