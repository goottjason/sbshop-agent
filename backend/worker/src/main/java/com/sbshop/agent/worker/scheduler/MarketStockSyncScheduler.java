package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.market.sync.MarketStockSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketStockSyncScheduler {
	private final MarketStockSyncService service;

	@Scheduled(fixedDelayString = "${products.stock-sync.dispatch-ms:10000}", scheduler = "marketInspectionTaskScheduler")
	public void dispatch() {
		try {
			service.dispatchSavedQuantities();
		} catch (Exception e) {
			log.error("저장된 판매용 수량 변경 접수 실패. 다음 실행에 재시도합니다.", e);
		}
	}

	@Scheduled(fixedDelayString = "${products.stock-sync.poll-ms:1000}", scheduler = "marketInspectionTaskScheduler")
	public void process() {
		for (var market : MarketStockSyncService.SUPPORTED) {
			try {
				service.processOne(market);
			} catch (Exception e) {
				log.error("판매용 수량 반영 처리 실패. 저장된 전송 의도와 임대 만료 후 재조회로 복구합니다: {}", market, e);
			}
		}
	}
}
