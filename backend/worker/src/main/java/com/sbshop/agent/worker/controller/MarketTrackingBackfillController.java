package com.sbshop.agent.worker.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sbshop.agent.core.application.order.service.MarketTrackingBackfillService;
import com.sbshop.agent.core.config.InternalAccessGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/internal/backfill")
@RequiredArgsConstructor
public class MarketTrackingBackfillController {

	private final MarketTrackingBackfillService backfillService;
	private final InternalAccessGuard internalAccessGuard;

	private static final int MAX_DAYS = 365;

	@PostMapping("/market-tracking")
	public ResponseEntity<Map<String, Object>> backfill(
		@RequestParam(defaultValue = "120")
		int days,
		@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false)
		String internalToken) {

		if (!internalAccessGuard.isAllowed(internalToken)) {
			log.warn("[내부트리거] 마켓 송장 백필 접근 거부 — 유효한 내부 토큰 없음");
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("ok", false, "message", "내부 토큰이 필요합니다."));
		}
		if (days < 1 || days > MAX_DAYS) {
			return ResponseEntity.badRequest()
				.body(Map.of("ok", false, "message", "days는 1~" + MAX_DAYS + " 범위여야 합니다: " + days));
		}

		log.info("[내부트리거] 마켓 보유 송장 백필 시작: 최근 {}일", days);
		backfillService.backfill(days);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("days", days);
		body.put("message", "백필을 백그라운드에서 시작했습니다(마켓별 안전 구간으로 나눠 순차 실행). "
			+ "진행·완료는 [백필] 로그로 확인하세요.");
		return ResponseEntity.ok(body);
	}
}
