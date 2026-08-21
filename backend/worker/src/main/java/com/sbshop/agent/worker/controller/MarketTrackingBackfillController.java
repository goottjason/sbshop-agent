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

/**
 * 내부 전용 트리거: <b>마켓 보유 송장 백필</b> — 네 마켓 동기화를 과거 구간까지 다시 돌린다.
 *
 * <p>배경(2026-08-08): {@code market_tracking_no}(마켓이 아는 송장)는 D-148에서 신설됐다. 그전에
 * 30일 조회 창을 벗어난 주문들은 이 값을 가질 기회가 없었고, 그래서 화면이 반영 여부를 판정하지 못해
 * `· 마켓 값 미확인`으로 남는다(2026-08-08 실측: 쿠팡 109 · 스토어 9). 반면 <b>창 안 주문은 전 마켓
 * 100% 수집</b>되고 있었다 — 진행 중 동작은 이미 일관돼 있어 새 수집 경로가 필요한 게 아니다.
 *
 * <p>그래서 새 경로를 만들지 않고 <b>검증된 동기화 경로를 과거 구간에 재실행</b>한다. 부수적으로
 * 그 기간의 상태·정산 정규화도 함께 최신화된다(모두 멱등 경로다).
 *
 * <p>운영 호출(기본 120일):
 * {@code docker exec projects-sbshop-api-1 curl -s -X POST 'localhost:8080/internal/backfill/market-tracking?days=120'}
 *
 * <p>구간 분할과 페이싱은 {@link MarketTrackingBackfillService}가 맡는다 — 한 번에 넓게 부르면
 * Cafe24는 조회 범위 3개월 상한(422), 쿠팡은 레이트리밋(429)에 걸린다(2026-08-08 실측).
 * 실행은 비동기라 트리거는 즉시 돌아오고, 진행·완료는 {@code [백필]} 로그로 확인한다.
 */
@Slf4j
@RestController
@RequestMapping("/internal/backfill")
@RequiredArgsConstructor
public class MarketTrackingBackfillController {

	private final MarketTrackingBackfillService backfillService;
	private final InternalAccessGuard internalAccessGuard;

	/** 조회 기간 상한 — 마켓 API 부담과 실수(예: days=100000)를 함께 막는다. */
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
