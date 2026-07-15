package com.sbshop.agent.worker.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
		try {
			emailFetcherService.fetchAndProcessEmails();
			return ResponseEntity.ok(Map.of("ok", true, "message", "email fetch triggered"));
		} catch (Exception e) {
			log.error("[내부트리거] 이메일 수집·처리 실패: {}", e.getMessage(), e);
			return ResponseEntity.internalServerError()
				.body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
		}
	}
}
