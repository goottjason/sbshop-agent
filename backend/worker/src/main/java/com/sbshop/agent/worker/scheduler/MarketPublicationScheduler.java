package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.market.sync.MarketPublicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketPublicationScheduler {
	private final MarketPublicationService service;

	@Scheduled(fixedDelayString = "${products.publication.poll-ms:1000}", scheduler = "marketInspectionTaskScheduler")
	public void process() {
		try {
			service.processOne();
		} catch (Exception e) {
			log.error("등록 작업 처리 실패. 전송 중단 작업은 자동 재등록하지 않고 생성 여부 확인으로 전환합니다.", e);
		}
	}
}
