package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.batch.BrandBackfillRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.ProductBarcodeBackfillService;
import com.sbshop.agent.core.application.product.ProductBrandBackfillService;
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

class BatchControllerBrandBackfillTest {

	private ProcessStatusService processStatusService;
	private ProductBrandBackfillService brandBackfillService;
	private BatchController controller;

	@BeforeEach
	void setUp() {
		processStatusService = Mockito.mock(ProcessStatusService.class);
		brandBackfillService = Mockito.mock(ProductBrandBackfillService.class);
		controller = new BatchController(
			Mockito.mock(BatchPriceStockService.class), processStatusService,
			Mockito.mock(ActionLogService.class), Mockito.mock(ApplicationEventPublisher.class),
			Mockito.mock(ProductBarcodeBackfillService.class), brandBackfillService);
	}

	@Test
	@DisplayName("D-261: 브랜드 백필 응답은 batchId·count·message 공통 키셋이고 count는 대상 수다")
	void backfillBrand_hasCommonKeyset() {
		when(brandBackfillService.findTargets(eq(VendorType.IHB), eq(50))).thenReturn(List.of(1L, 2L, 3L));
		when(processStatusService.startBatch(eq(JobType.BACKFILL_BRAND), any())).thenReturn("batch-20");

		ResponseEntity<Map<String, String>> resp =
			controller.backfillBrand(new BrandBackfillRequest("IHB", 50));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "batch-20")
			.containsEntry("count", "3");
		verify(brandBackfillService).backfillBrands(eq("batch-20"), eq(List.of(1L, 2L, 3L)), any());
	}

	@Test
	@DisplayName("D-261: 소싱처를 비우면 전 소싱처를 대상으로 선정한다")
	void backfillBrand_withoutVendor_scansAll() {
		when(brandBackfillService.findTargets(eq(null), eq(0))).thenReturn(List.of(7L));
		when(processStatusService.startBatch(eq(JobType.BACKFILL_BRAND), any())).thenReturn("batch-21");

		controller.backfillBrand(new BrandBackfillRequest(null, 0));

		verify(brandBackfillService).findTargets(null, 0);
	}

	@Test
	@DisplayName("D-261: 대상이 없으면 배치를 시작하지 않고 빈 batchId를 돌려준다")
	void backfillBrand_noTargets_returnsEmptyBatchId() {
		when(brandBackfillService.findTargets(any(), eq(0))).thenReturn(List.of());

		ResponseEntity<Map<String, String>> resp =
			controller.backfillBrand(new BrandBackfillRequest("IHB", 0));

		assertThat(resp.getBody())
			.containsOnlyKeys("batchId", "count", "message")
			.containsEntry("batchId", "")
			.containsEntry("count", "0");
		verify(processStatusService, never()).startBatch(any(), any());
		verify(brandBackfillService, never()).backfillBrands(any(), any(), any());
	}

	@Test
	@DisplayName("D-261: 알 수 없는 소싱처 코드는 거부한다")
	void backfillBrand_unknownVendor_isRejected() {
		assertThatThrownBy(() -> controller.backfillBrand(new BrandBackfillRequest("XXX", 0)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
