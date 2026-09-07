package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.StockCrawlerRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchScheduler {

	private final BatchPriceStockService batchPriceStockService;
	private final ProcessStatusService processStatusService;
	private final StockCrawlerRouter stockCrawlerRouter;

	/** No scheduler is registered. Re-enabling automatic source writes requires a reviewed policy contract. */
	public void scheduleDailyPriceUpdate() {
		throw new UnsupportedOperationException("소싱 가격·재고 즉시 배치는 중단되었습니다. 수집 결과를 검토한 뒤 저장하세요.");
	}
}
