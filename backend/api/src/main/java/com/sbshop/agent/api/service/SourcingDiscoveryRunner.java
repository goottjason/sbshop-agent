package com.sbshop.agent.api.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingDiscoveryUseCase;
import com.sbshop.agent.core.application.sourcing.dto.DiscoverySummary;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SourcingDiscoveryRunner {
	private final SourcingDiscoveryUseCase discoveryUseCase;
	private final ActionLogService actionLogService;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicReference<DiscoverySummary> lastSummary = new AtomicReference<>();

	public boolean tryStart() {
		return running.compareAndSet(false, true);
	}

	@Async
	public void runAsync() {
		actionLogService.record(ActionLogConstants.SOURCING_DISCOVERY, null,
			ActionStatus.STARTED, "소싱 후보 발굴 시작");
		try {
			DiscoverySummary summary = discoveryUseCase.run();
			lastSummary.set(summary);
			actionLogService.record(ActionLogConstants.SOURCING_DISCOVERY, null,
				ActionStatus.SUCCESS,
				"발굴 완료 — 수집 %d · 추천대상 %d · 통관차단 %d · 경고 %d".formatted(
					summary.crawled(), summary.scored(), summary.customsBlocked(),
					summary.warnings().size()));
		} catch (Exception e) {
			log.error("[소싱발굴] 실행 실패", e);
			actionLogService.record(ActionLogConstants.SOURCING_DISCOVERY, null,
				ActionStatus.FAILED, "발굴 실패: " + e.getMessage());
		} finally {
			running.set(false);
		}
	}

	public void abort() {
		running.set(false);
	}

	public boolean isRunning() {
		return running.get();
	}

	public DiscoverySummary lastSummary() {
		return lastSummary.get();
	}
}
