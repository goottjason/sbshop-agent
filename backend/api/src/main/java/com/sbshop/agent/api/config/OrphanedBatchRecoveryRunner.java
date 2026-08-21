package com.sbshop.agent.api.config;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
