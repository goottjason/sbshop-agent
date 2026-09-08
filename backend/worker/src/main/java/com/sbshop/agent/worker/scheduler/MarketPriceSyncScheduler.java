package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.market.sync.MarketPriceSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketPriceSyncScheduler {
	private final MarketPriceSyncService service;

	@Scheduled(fixedDelayString = "${products.price-sync.dispatch-ms:10000}", scheduler = "marketInspectionTaskScheduler")
	public void dispatch() {
		try {
			service.dispatchSavedPrices();
		} catch (Exception e) {
			log.error("저장된 가격 변경 접수 실패. 다음 실행에 재시도합니다.", e);
		}
	}

	public void process() {
		for (var market : MarketPriceSyncService.SUPPORTED) {
			try {
				service.processOne(market);
			} catch (Exception e) {
				log.error("가격 반영 처리 실패. 저장된 전송 의도와 임대 만료 후 재조회로 복구합니다: {}", market, e);
			}
		}
	}
}
