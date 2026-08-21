package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchScheduler {

	private final BatchPriceStockService batchPriceStockService;
	private final ProcessStatusService processStatusService;

	// D-093(2026-07-21, 사용자 결정): 정기 자동 재가격 비활성화.
	// 사유: 이 배치가 하드코딩 파라미터(margin 15 / coupon 20 / minMargin 5000)로 매일 05:00 전 상품을
	// 재가격하여, 사용자가 수동 배치로 설정한 판매가정책(예: 10/15/3500)을 몇 시간 만에 덮어써 왔다.
	// 현재는 수동 배치 업데이트만 사용한다. 재활성 시(예: 매출 급감 대응 자동화)에는 하드코딩 상수 대신
	// 정책 저장소를 읽도록 고도화 후 @Scheduled를 복원할 것.
	// @Scheduled(cron = "0 0 5 * * ?", zone = "Asia/Seoul")
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
