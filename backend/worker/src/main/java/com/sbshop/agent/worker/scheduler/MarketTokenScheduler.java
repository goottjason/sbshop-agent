package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.market.port.Cafe24TokenRefreshPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 마켓 OAuth 토큰 유지보수 스케줄러(D-103).
 *
 * <p>Cafe24 리프레시 토큰은 유효 2주이며 refresh 때마다 회전·연장된다. 토큰 갱신을 주문
 * 동기화 트래픽에만 의존하면(온디맨드) 2주 이상 API 호출 공백 시 리프레시 토큰이 만료되어
 * 재인증 외 복구가 불가능해진다. 트래픽과 독립적으로 매일 1회 강제 회전해 시한을 항상 갱신한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketTokenScheduler {

	private final Cafe24TokenRefreshPort cafe24TokenRefreshPort;

	// 매일 03:00(KST) - Cafe24 리프레시 토큰 선제 회전.
	@Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Seoul")
	public void refreshCafe24Token() {
		log.info("Cafe24 리프레시 토큰 선제 갱신 트리거...");
		cafe24TokenRefreshPort.refreshProactively();
	}
}
