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

@Slf4j
@RestController
@RequestMapping("/internal/email")
@RequiredArgsConstructor
public class EmailFetchController {

	private final EmailFetcherService emailFetcherService;
	private final InternalAccessGuard internalAccessGuard;
	private final SyncStatusService syncStatusService;

	@PostMapping("/fetch")
	public ResponseEntity<Map<String, Object>> fetch(
		@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false)
		String internalToken) {
		if (!internalAccessGuard.isAllowed(internalToken)) {
			log.warn("[내부트리거] 이메일 수집·처리 접근 거부 — 유효한 내부 토큰 없음");
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("ok", false, "message", "forbidden: invalid internal token"));
		}
		log.info("[내부트리거] 이메일 수집·처리 수동 실행 요청");
		syncStatusService.markRunning(SyncMarketKeys.EMAIL);
		try {
			boolean executed = emailFetcherService.fetchAndProcessEmails();
			if (executed) {
				syncStatusService.markCompleted(SyncMarketKeys.EMAIL);
			}
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
