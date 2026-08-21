package com.sbshop.agent.worker.controller;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sbshop.agent.core.application.product.MarketLinkIdentifierBackfillService;
import com.sbshop.agent.core.config.InternalAccessGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/internal/backfill")
@RequiredArgsConstructor
public class MarketLinkBackfillController {

	private final MarketLinkIdentifierBackfillService backfillService;
	private final InternalAccessGuard internalAccessGuard;

	private final AtomicBoolean running = new AtomicBoolean(false);

	@PostMapping("/market-link-ids")
	public ResponseEntity<Map<String, Object>> backfill(
		@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false)
		String internalToken,
		@RequestParam(value = "limit", defaultValue = "0")
		int limit) {
		if (!internalAccessGuard.isAllowed(internalToken)) {
			log.warn("[내부트리거] 링크식별자 백필 접근 거부 — 유효한 내부 토큰 없음");
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("ok", false, "message", "forbidden: invalid internal token"));
		}
		if (!running.compareAndSet(false, true)) {
			return ResponseEntity.ok(Map.of("ok", true, "started", false, "message", "already running"));
		}
		log.info("[내부트리거] 마켓 링크식별자 백필 백그라운드 실행 시작 (limit={})", limit);
		backfillService.backfillAllAsync(limit, () -> running.set(false));
		return ResponseEntity.ok(Map.of("ok", true, "started", true,
			"message", "backfill started in background; check DB for progress"));
	}
}
