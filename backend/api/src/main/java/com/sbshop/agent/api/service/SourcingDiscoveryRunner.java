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

/**
 * 발굴 비동기 실행기.
 *
 * <p><b>별도 빈이어야 한다.</b> {@code @Async}는 스프링 프록시로 동작하므로 같은 빈 안에서
 * 호출하면(self-invocation) 프록시를 우회해 그냥 동기 실행된다 — 컨트롤러가 수 분간 블록되고
 * HTTP 타임아웃이 난다. 컨트롤러에서 이 빈을 주입받아 호출해야 실제로 비동기가 된다.
 *
 * <p>중복 실행 가드를 여기 둔다. 발굴은 iHerb에 브라우저 렌더를 수십 회 날리므로 동시 실행은
 * 불필요한 부하이자 서로의 결과를 덮어쓰는 원인이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourcingDiscoveryRunner {

	private final SourcingDiscoveryUseCase discoveryUseCase;
	private final ActionLogService actionLogService;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicReference<DiscoverySummary> lastSummary = new AtomicReference<>();

	/** @return 실행을 시작했으면 true, 이미 돌고 있으면 false */
	public boolean tryStart() {
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		try {
			runAsync();
			return true;
		} catch (RuntimeException e) {
			// 비동기 제출 자체가 실패하면 플래그가 영원히 켜진 채 남는다.
			running.set(false);
			throw e;
		}
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

	public boolean isRunning() {
		return running.get();
	}

	public DiscoverySummary lastSummary() {
		return lastSummary.get();
	}
}
