package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.batch.CrawlAndUpdateRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateAllRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateRequest;
import com.sbshop.agent.api.dto.batch.SupplierBatchRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.ProductBarcodeBackfillService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

class BatchControllerTriggerKeysetContractTest {

	private BatchPriceStockService batchPriceStockService;
	private ProcessStatusService processStatusService;
	private BatchController controller;

	@BeforeEach
	void setUp() {
		batchPriceStockService = Mockito.mock(BatchPriceStockService.class);
		processStatusService = Mockito.mock(ProcessStatusService.class);
		controller = new BatchController(batchPriceStockService, processStatusService,
			Mockito.mock(ActionLogService.class), Mockito.mock(ApplicationEventPublisher.class),
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
	@DisplayName("crawl-and-update 응답은 batchId·count·message 공통 키셋이고 count는 대상 상품 수다")
	void crawlAndUpdate_hasCommonKeyset() {
		when(processStatusService.startBatch(eq(JobType.CRAWL_AND_UPDATE_PRICE_STOCK), any()))
			.thenReturn("batch-1");

		ResponseEntity<Map<String, String>> resp = controller.crawlAndUpdate(
			new CrawlAndUpdateRequest(List.of(10L, 20L), null, null, null));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "batch-1")
			.containsEntry("count", "2");
	}

	@Test
	@DisplayName("manual-update-price-stock 응답은 batchId·count·message 공통 키셋이고 count는 항목 수다")
	void manualUpdate_hasCommonKeyset() {
		when(processStatusService.startBatch(eq(JobType.MANUAL_UPDATE_PRICE_STOCK), any()))
			.thenReturn("batch-2");
		List<PriceStockItem> items = List.of(
			new PriceStockItem(5L, new BigDecimal("100"), 3),
			new PriceStockItem(6L, new BigDecimal("200"), 4));

		ResponseEntity<Map<String, String>> resp = controller.manualUpdate(new ManualUpdateRequest(items));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "batch-2")
			.containsEntry("count", "2");
	}

	@Test
	@DisplayName("manual-update-price-stock items가 null이면 count는 0이다")
	void manualUpdate_nullItems_countIsZero() {
		when(processStatusService.startBatch(eq(JobType.MANUAL_UPDATE_PRICE_STOCK), any()))
			.thenReturn("batch-2n");

		ResponseEntity<Map<String, String>> resp = controller.manualUpdate(new ManualUpdateRequest(null));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("count", "0");
	}

	@Test
	@DisplayName("manual-update-all 응답은 batchId·count·message 공통 키셋이고 count는 대상 상품 수다")
	void manualUpdateAll_hasCommonKeyset() {
		when(processStatusService.startBatch(eq(JobType.MANUAL_UPDATE_ALL_FIELDS), any()))
			.thenReturn("batch-3");

		ResponseEntity<Map<String, String>> resp = controller.manualUpdateAll(
			new ManualUpdateAllRequest(List.of(7L, 8L, 9L),
				List.of(emptyCommand(), emptyCommand(), emptyCommand())));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "batch-3")
			.containsEntry("count", "3");
	}

	@Test
	@DisplayName("by-supplier 응답은 batchId·count·message 공통 키셋을 유지한다")
	void updateBySupplier_hasCommonKeyset() {
		when(batchPriceStockService.getProductIdsByVendor(VendorType.IHB)).thenReturn(List.of(1L, 2L, 3L));
		when(processStatusService.startBatch(eq(JobType.CRAWL_AND_UPDATE_PRICE_STOCK), any()))
			.thenReturn("batch-4");

		ResponseEntity<Map<String, String>> resp = controller.updateBySupplier(
			new SupplierBatchRequest("ihb", null, null, null));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "batch-4")
			.containsEntry("count", "3");
	}

	@Test
	@DisplayName("by-supplier 0건 응답도 batchId·count·message 공통 키셋을 유지한다")
	void updateBySupplier_empty_hasCommonKeyset() {
		when(batchPriceStockService.getProductIdsByVendor(VendorType.IHB)).thenReturn(List.of());

		ResponseEntity<Map<String, String>> resp = controller.updateBySupplier(
			new SupplierBatchRequest("ihb", null, null, null));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("count", "0");
	}
}
