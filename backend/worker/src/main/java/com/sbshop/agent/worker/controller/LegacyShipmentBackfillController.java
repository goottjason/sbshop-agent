package com.sbshop.agent.worker.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbshop.agent.core.application.order.service.LegacyShipmentBackfillService;
import com.sbshop.agent.core.config.InternalAccessGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 내부 전용 트리거: 배송에 속하지 않은 옛 라인아이템에 배송을 만들어 연결한다(6단계 전제).
 * nginx 미노출. 마켓 API를 부르지 않아 수백 건도 즉시 끝나므로 동기 실행하고 결과를 바로 돌려준다.
 *
 * <p>운영 호출:
 * {@code docker exec projects-sbshop-api-1 curl -s -X POST localhost:8080/internal/backfill/legacy-shipments}
 */
@Slf4j
@RestController
@RequestMapping("/internal/backfill")
@RequiredArgsConstructor
public class LegacyShipmentBackfillController {

	private final LegacyShipmentBackfillService backfillService;
	private final InternalAccessGuard internalAccessGuard;

	@PostMapping("/legacy-shipments")
	public ResponseEntity<Map<String, Object>> backfill(
		@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false) String internalToken) {
		if (!internalAccessGuard.isAllowed(internalToken)) {
			log.warn("[내부트리거] 레거시 배송 백필 접근 거부 — 유효한 내부 토큰 없음");
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("ok", false, "message", "forbidden: invalid internal token"));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.putAll(backfillService.backfill());
		return ResponseEntity.ok(result);
	}
}
