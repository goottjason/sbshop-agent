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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class BatchControllerCrawlValidationTest {

	@Mock
	private BatchPriceStockService batchPriceStockService;
	@Mock
	private ProcessStatusService processStatusService;
	@Mock
	private ActionLogService actionLogService;

	private BatchController controller() {
		return new BatchController(batchPriceStockService, processStatusService, actionLogService,
			Mockito.mock(ApplicationEventPublisher.class));
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
