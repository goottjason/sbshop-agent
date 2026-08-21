package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.market.port.Cafe24TokenRefreshPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketTokenScheduler {

	private final Cafe24TokenRefreshPort cafe24TokenRefreshPort;

	@Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Seoul")
	public void refreshCafe24Token() {
		log.info("Cafe24 리프레시 토큰 선제 갱신 트리거...");
		cafe24TokenRefreshPort.refreshProactively();
	}
}
