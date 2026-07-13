package com.sbshop.agent.worker.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

	/** iHerb 이메일 수집·송장 대조/배송 처리를 즉시 1회 실행 */
	@PostMapping("/fetch")
	public ResponseEntity<Map<String, Object>> fetch() {
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
