package com.sbshop.agent.worker.controller;

import java.util.Map;

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

/**
 * 내부 전용 트리거: 상품 그리드 마켓 링크에 필요한 부가 식별자(쿠팡 productId, 스토어 channelProductNo)를
 * 마켓 API로 조회해 market_identifiers에 백필한다. nginx 미노출.
 * 운영 호출: {@code docker exec projects-sbshop-api-1 curl -s -X POST 'localhost:8080/internal/backfill/market-link-ids?limit=0'}
 */
@Slf4j
@RestController
@RequestMapping("/internal/backfill")
@RequiredArgsConstructor
public class MarketLinkBackfillController {

	private final MarketLinkIdentifierBackfillService backfillService;
	private final InternalAccessGuard internalAccessGuard;

	@PostMapping("/market-link-ids")
	public ResponseEntity<Map<String, Object>> backfill(
			@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false) String internalToken,
			@RequestParam(value = "limit", defaultValue = "0") int limit) {
		if (!internalAccessGuard.isAllowed(internalToken)) {
			log.warn("[내부트리거] 링크식별자 백필 접근 거부 — 유효한 내부 토큰 없음");
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("ok", false, "message", "forbidden: invalid internal token"));
		}
		log.info("[내부트리거] 마켓 링크식별자 백필 실행 요청 (limit={})", limit);
		try {
			Map<String, Object> result = backfillService.backfillAll(limit);
			return ResponseEntity.ok(Map.of("ok", true, "result", result));
		} catch (Exception e) {
			log.error("[내부트리거] 링크식별자 백필 실패: {}", e.getMessage(), e);
			return ResponseEntity.internalServerError()
				.body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
		}
	}
}
