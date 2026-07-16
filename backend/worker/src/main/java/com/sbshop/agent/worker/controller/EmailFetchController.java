package com.sbshop.agent.worker.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.config.InternalAccessGuard;
import com.sbshop.agent.worker.service.EmailFetcherService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 내부(컨테이너 로컬, 8081) 전용 트리거 엔드포인트.
 * 이메일 IMAP 수집·송장 처리를 스케줄러(:00/:30) 대기 없이 즉시 실행한다.
 * nginx에 노출되지 않으며 운영/E2E 검증용으로만 docker exec curl localhost:8081 로 호출한다.
 */
@Slf4j
@RestController
@RequestMapping("/internal/email")
@RequiredArgsConstructor
public class EmailFetchController {

	private final EmailFetcherService emailFetcherService;
	// F-MISC-17: 공유시크릿 헤더 가드. INTERNAL_API_TOKEN 미설정 시 비활성(무파손).
	private final InternalAccessGuard internalAccessGuard;
	// F-MISC-19: 수동 경로도 스케줄러(OrderSyncScheduler.syncOrders)와 동일 키(SyncMarketKeys.EMAIL)로
	// SyncStatus를 기록해 /orders/sync/status 계약을 일관되게 유지한다.
	private final SyncStatusService syncStatusService;

	/** iHerb 이메일 수집·송장 대조/배송 처리를 즉시 1회 실행 */
	@PostMapping("/fetch")
	public ResponseEntity<Map<String, Object>> fetch(
			@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false) String internalToken) {
		// F-MISC-17: 가드 활성 시 시크릿 헤더 불일치/누락은 서비스 실행 전 403으로 차단.
		if (!internalAccessGuard.isAllowed(internalToken)) {
			log.warn("[내부트리거] 이메일 수집·처리 접근 거부 — 유효한 내부 토큰 없음");
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("ok", false, "message", "forbidden: invalid internal token"));
		}
		log.info("[내부트리거] 이메일 수집·처리 수동 실행 요청");
		// F-MISC-19: 스케줄러 경로와 동일하게 markRunning→markCompleted/markFailed로 상태 기록.
		syncStatusService.markRunning(SyncMarketKeys.EMAIL);
		try {
			// F-MISC-20: 서비스가 실제로 본처리를 수행했는지(executed) 여부를 응답에 반영.
			// 재진입 가드로 이미 실행 중이면 false(스킵) → 항상-ok:true 오탐 제거.
			boolean executed = emailFetcherService.fetchAndProcessEmails();
			syncStatusService.markCompleted(SyncMarketKeys.EMAIL);
			return ResponseEntity.ok(Map.of(
				"ok", true,
				"executed", executed,
				"message", executed ? "email fetch triggered" : "skipped: fetch already in progress"));
		} catch (Exception e) {
			syncStatusService.markFailed(SyncMarketKeys.EMAIL, e.getMessage());
			log.error("[내부트리거] 이메일 수집·처리 실패: {}", e.getMessage(), e);
			return ResponseEntity.internalServerError()
				.body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
		}
	}
}
