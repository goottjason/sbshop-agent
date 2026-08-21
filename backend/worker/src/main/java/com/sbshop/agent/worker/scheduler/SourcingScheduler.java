package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.sourcing.customs.BannedIngredientSyncService;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingConfigService;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingDiscoveryUseCase;
import com.sbshop.agent.core.application.sourcing.dto.DiscoverySummary;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourcingScheduler {

	private final SourcingDiscoveryUseCase discoveryUseCase;
	private final BannedIngredientSyncService bannedIngredientSyncService;
	private final SourcingConfigService configService;
	private final ActionLogService actionLogService;

	private final AtomicBoolean discoveryRunning = new AtomicBoolean(false);

	@Value("${sourcing.scheduler.enabled:true}")
	private boolean schedulerEnabled;

	@Scheduled(cron = "${sourcing.scheduler.banned-cron:0 30 2 * * *}")
	public void syncBannedIngredients() {
		if (!schedulerEnabled)
			return;
		try {
			BannedIngredientSyncService.SyncResult result = bannedIngredientSyncService.sync();
			actionLogService.record(ActionLogConstants.BANNED_INGREDIENT_SYNC, null,
				result.ok() ? ActionStatus.SUCCESS : ActionStatus.FAILED,
				result.ok()
					? "반입차단 성분 동기화 — 신규 %d · 갱신 %d · 차단중 %d".formatted(
						result.created(), result.updated(), result.activeCount())
					: "반입차단 성분 동기화 실패(기존 %d건 유지): %s".formatted(
						result.activeCount(), result.error()));
		} catch (Exception e) {
			log.error("[스케줄러] 반입차단 성분 동기화 실패", e);
			actionLogService.record(ActionLogConstants.BANNED_INGREDIENT_SYNC, null,
				ActionStatus.FAILED, "반입차단 성분 동기화 예외: " + e.getMessage());
		}
	}

	@Scheduled(cron = "${sourcing.scheduler.discovery-cron:0 0 3 * * *}")
	public void runDiscovery() {
		if (!schedulerEnabled)
			return;
		if (!Boolean.TRUE.equals(configService.getOrCreate().getScheduleEnabled())) {
			log.info("[스케줄러] 소싱 발굴이 설정에서 비활성화돼 있어 건너뜁니다.");
			return;
		}
		if (!discoveryRunning.compareAndSet(false, true)) {
			log.warn("[스케줄러] 이전 발굴이 아직 실행 중 — 이번 회차를 건너뜁니다.");
			return;
		}
		actionLogService.record(ActionLogConstants.SOURCING_DISCOVERY, null,
			ActionStatus.STARTED, "정기 소싱 후보 발굴 시작");
		try {
			DiscoverySummary summary = discoveryUseCase.run();
			actionLogService.record(ActionLogConstants.SOURCING_DISCOVERY, null,
				ActionStatus.SUCCESS,
				"정기 발굴 완료 — 수집 %d · 추천대상 %d · 통관차단 %d · 경고 %d".formatted(
					summary.crawled(), summary.scored(), summary.customsBlocked(),
					summary.warnings().size()));
		} catch (Exception e) {
			log.error("[스케줄러] 소싱 발굴 실패", e);
			actionLogService.record(ActionLogConstants.SOURCING_DISCOVERY, null,
				ActionStatus.FAILED, "정기 발굴 실패: " + e.getMessage());
		} finally {
			discoveryRunning.set(false);
		}
	}
}
