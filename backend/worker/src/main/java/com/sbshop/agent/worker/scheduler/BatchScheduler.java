package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchScheduler {

	private final BatchPriceStockService batchPriceStockService;
	private final ProcessStatusService processStatusService;

	@Scheduled(cron = "0 0 5 * * ?")
	public void scheduleDailyIherbPriceUpdate() {
		log.info("iHerb 소싱업체 정기 가격/재고 업데이트 시작...");
		List<Long> productIds = batchPriceStockService.getProductIdsByVendor(VendorType.IHB);
		if (productIds.isEmpty()) {
			log.info("iHerb 상품이 없습니다. 정기 업데이트를 건너뜁니다.");
			return;
		}
		List<String> productCodes = productIds.stream().map(String::valueOf).toList();
		String batchId = processStatusService.startBatch(
			com.sbshop.agent.core.domain.process.enums.JobType.CRAWL_AND_UPDATE_PRICE_STOCK,
			productCodes);
		batchPriceStockService.crawlAndUpdatePriceStock(
			batchId, productIds,
			new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000"),
			ActionLogConstants.BATCH_CRAWL_UPDATE);
		log.info("iHerb 정기 가격/재고 업데이트 배치 시작: batchId={}, count={}", batchId, productIds.size());
	}
}
