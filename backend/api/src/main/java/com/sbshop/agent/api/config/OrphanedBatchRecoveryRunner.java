package com.sbshop.agent.api.config;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * api 부팅 완료 시 고아 PENDING 배치 상태를 복구한다(F-BATCH-2).
 *
 * <p>배치는 api JVM에서 실행되고 배포는 api를 재시작하므로 냉기동 시점엔 진행 중 배치가 없다.
 * 따라서 부팅 때 존재하는 PENDING은 이전(죽은) 실행의 고아이며, 방치하면 배치 요약이 영원히
 * 미완료로 남는다. worker가 아니라 api에 둔다(배치 실행 주체가 api이고, worker와의 부팅 경쟁 회피).
 * {@link ApplicationReadyEvent}로 부팅 1회만 실행하며 스케줄러/실시간 경로에서는 호출하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanedBatchRecoveryRunner {

	private final ProcessStatusService processStatusService;

	@EventListener(ApplicationReadyEvent.class)
	public void recoverOnStartup() {
		int recovered = processStatusService.recoverOrphanedPending();
		if (recovered > 0) {
			log.warn("부팅 시 고아 PENDING 배치 {}건을 FAILED로 복구했습니다", recovered);
		}
	}
}
