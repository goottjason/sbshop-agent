package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.api.dto.batch.CrawlAndUpdateRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-BATCH-4: crawl-and-update 요청의 productIds가 null/빈이면 진입부에서 NPE(500) 또는
 * 빈 배치가 조용히 생성된다. 사용자 입력 오류이므로 IllegalArgumentException(400)으로 거부한다.
 */
@ExtendWith(MockitoExtension.class)
class BatchControllerCrawlValidationTest {

	@Mock private BatchPriceStockService batchPriceStockService;
	@Mock private ProcessStatusService processStatusService;
	@Mock private ActionLogService actionLogService;

	private BatchController controller() {
		return new BatchController(batchPriceStockService, processStatusService, actionLogService);
	}

	private CrawlAndUpdateRequest requestWith(List<Long> productIds) {
		return new CrawlAndUpdateRequest(productIds, null, null, null);
	}

	@Test
	@DisplayName("productIds null → IllegalArgumentException(400), NPE(500) 아님")
	void nullProductIds_throwsIllegalArgument() {
		assertThatThrownBy(() -> controller().crawlAndUpdate(requestWith(null)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("productIds 빈 목록 → IllegalArgumentException(400)")
	void emptyProductIds_throwsIllegalArgument() {
		assertThatThrownBy(() -> controller().crawlAndUpdate(requestWith(List.of())))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
