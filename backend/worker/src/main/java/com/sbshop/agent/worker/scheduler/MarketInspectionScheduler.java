package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.market.inspection.MarketInspectionService;
import com.sbshop.agent.core.application.market.inspection.DailyMarketInspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketInspectionScheduler {
	private final MarketInspectionService inspections;
	private final DailyMarketInspectionService daily;

	@Scheduled(fixedDelayString = "${products.connection-inspection.enrollment-poll-ms:60000}", scheduler = "marketInspectionTaskScheduler")
	public void enroll() {
		try {
			daily.tick();
		} catch (Exception e) {
			log.error("정기 마켓 상태 확인 접수 실패. 저장된 진행 위치에서 다음 실행에 재시도합니다.", e);
		}
	}

	@Scheduled(fixedDelayString = "${products.connection-inspection.poll-ms:1000}", scheduler = "marketInspectionTaskScheduler")
	public void process() {
		try {
			for (var market : MarketInspectionService.SUPPORTED)
				inspections.processOne(market);
		} catch (Exception e) {
			log.error("마켓 상태 확인 작업 처리 실패. 저장된 임대 만료 후 복구됩니다.", e);
		}
	}

}
