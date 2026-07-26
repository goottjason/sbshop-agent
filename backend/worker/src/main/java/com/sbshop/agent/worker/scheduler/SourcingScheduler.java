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

/**
 * 소싱 자동화 스케줄러 — 반입차단 성분 동기화(02:30)와 후보 발굴(03:00).
 *
 * <p>성분 동기화를 발굴보다 먼저 돌린다. 통관 게이트가 최신 목록으로 판정해야 새로 지정된
 * 차단 성분이 그날 발굴분부터 걸린다.
 *
 * <p>발굴은 브라우저 렌더 크롤이 수십 분 걸리므로 사용자 트래픽이 없는 새벽에 돌린다.
 * 실행 여부는 {@code sb_sourcing_config.schedule_enabled}로 런타임에 끌 수 있다 —
 * 배포 없이 멈출 수 있어야 한다(iHerb 차단·API 한도 소진 등 긴급 상황 대비).
 *
 * <p>크론 표현식은 설정 테이블에도 있지만 {@code @Scheduled}는 정적이라 여기서는 프로퍼티를 쓴다.
 * 시각 자체를 바꾸려면 환경변수를 고치고 재배포해야 한다(설정 테이블 값은 화면 표시·문서용).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourcingScheduler {

	private final SourcingDiscoveryUseCase discoveryUseCase;
	private final BannedIngredientSyncService bannedIngredientSyncService;
	private final SourcingConfigService configService;
	private final ActionLogService actionLogService;

	/** 스케줄 실행이 겹치지 않게 막는다(전 회차가 아직 도는데 다음 회차가 시작되는 상황). */
	private final AtomicBoolean discoveryRunning = new AtomicBoolean(false);

	@Value("${sourcing.scheduler.enabled:true}")
	private boolean schedulerEnabled;

	/** 반입차단 성분 동기화 — 매일 02:30. */
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

	/** 후보 발굴 — 매일 03:00. */
	@Scheduled(cron = "${sourcing.scheduler.discovery-cron:0 0 3 * * *}")
	public void runDiscovery() {
		if (!schedulerEnabled)
			return;
		// 런타임 스위치 — 배포 없이 끌 수 있어야 한다.
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
