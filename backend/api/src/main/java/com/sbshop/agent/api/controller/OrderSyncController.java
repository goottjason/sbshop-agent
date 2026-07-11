package com.sbshop.agent.api.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.EsmplusOrderSyncService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.infrastructure.client.esmplus.EsmplusScraper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/orders/sync")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OrderSyncController {

	// 쿠팡 주문 동기화 서비스 의존성
	private final CoupangOrderSyncService coupangOrderSyncService;
	// 스마트스토어 주문 동기화 서비스 의존성
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;
	// 11번가 주문 동기화 서비스 의존성
	private final ElevenstOrderSyncService elevenstOrderSyncService;
	// ESM+(G마켓/옥션) 주문 동기화 서비스 의존성
	private final EsmplusOrderSyncService esmplusOrderSyncService;

	private final Cafe24OrderSyncService cafe24OrderSyncService;

	private final Cafe24OrderApiPort cafe24OrderApiPort;
	// 통관 상태 동기화 서비스 의존성
	private final CustomsOrderSyncService customsOrderSyncService;
	// ESM+ 웹 스크래퍼
	private final EsmplusScraper esmplusScraper;
	// 동기화 상태 추적 서비스
	private final SyncStatusService syncStatusService;
	// 사용자 액션 로그 서비스 (D-042)
	private final ActionLogService actionLogService;

	// 쿠팡 동기화 POST 엔드포인트 매핑
	@PostMapping("/coupang")
	public ResponseEntity<Map<String, Object>> syncCoupangOrders() {
		// 쿠팡 동기화 요청 수신 로그 출력
		log.info("쿠팡 주문 동기화 요청 수신됨");
		actionLogService.record("COUPANG_SYNC", "COUPANG", ActionStatus.STARTED, "쿠팡 동기화 요청");

		try {
			// 쿠팡 동기화 비동기 실행 호출
			coupangOrderSyncService.syncCoupangOrders();
			// 성공 응답 및 메시지 반환
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "쿠팡 주문 동기화가 백그라운드에서 시작되었습니다."));
		} catch (Exception e) {
			// 쿠팡 동기화 실패 에러 로그 출력
			log.error("쿠팡 주문 동기화 실패", e);
			// 내부 서버 오류 응답 및 에러 메시지 반환
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "쿠팡 주문 동기화 실패: " + e.getMessage()));
		}
	}

	// 스마트스토어 동기화 POST 엔드포인트 매핑
	@PostMapping("/smartstore")
	public ResponseEntity<Map<String, Object>> syncSmartStoreOrders() {
		// 스마트스토어 동기화 요청 수신 로그 출력
		log.info("스마트스토어 주문 동기화 요청 수신됨");
		actionLogService.record("SMART_STORE_SYNC", "SMART_STORE", ActionStatus.STARTED,
			"스마트스토어 동기화 요청");

		try {
			// 스마트스토어 동기화 비동기 실행 호출
			smartStoreOrderSyncService.syncSmartStoreOrders();
			// 성공 응답 및 메시지 반환
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "스마트스토어 주문 동기화가 백그라운드에서 시작되었습니다."));
		} catch (Exception e) {
			// 스마트스토어 동기화 실패 에러 로그 출력
			log.error("스마트스토어 주문 동기화 실패", e);
			// 내부 서버 오류 응답 및 에러 메시지 반환
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "스마트스토어 주문 동기화 실패: " + e.getMessage()));
		}
	}

	// 11번가 동기화 POST 엔드포인트 매핑
	@PostMapping("/elevenstreet")
	public ResponseEntity<Map<String, Object>> syncElevenStreetOrders() {
		log.info("11번가 주문 동기화 요청 수신됨");
		actionLogService.record("ELEVEN_STREET_SYNC", "ELEVEN_STREET", ActionStatus.STARTED,
			"11번가 동기화 요청");

		try {
			elevenstOrderSyncService.syncElevenstOrders();
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "11번가 주문 동기화가 백그라운드에서 시작되었습니다."));
		} catch (Exception e) {
			log.error("11번가 주문 동기화 실패", e);
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "11번가 주문 동기화 실패: " + e.getMessage()));
		}
	}

	// G마켓/옥션 동기화 — Selenium(ESM+) 대신 Cafe24 주문 API로 선회(order_place_id=gmarket/auction).
	@PostMapping("/esmplus")
	public ResponseEntity<Map<String, Object>> syncEsmplusOrders() {
		log.info("G마켓/옥션(Cafe24 주문API) 동기화 요청 수신됨");
		actionLogService.record("GMARKET_SYNC", "GMARKET", ActionStatus.STARTED,
			"G마켓/옥션(Cafe24 주문API) 동기화 요청");

		try {
			cafe24OrderSyncService.syncCafe24Orders();
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "G마켓/옥션 주문 동기화(Cafe24 API)가 백그라운드에서 시작되었습니다."));
		} catch (Exception e) {
			log.error("G마켓/옥션(Cafe24) 주문 동기화 실패", e);
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "G마켓/옥션 주문 동기화 실패: " + e.getMessage()));
		}
	}

	// 진단: Cafe24 주문 API 원시 응답 프리뷰(최근 7일 first page). 파싱 검증·구조 확인용.
	@PostMapping("/cafe24/preview")
	public ResponseEntity<Object> previewCafe24Orders() {
		try {
			java.time.LocalDate to = java.time.LocalDate.now();
			java.time.LocalDate from = to.minusDays(7);
			java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
			var orders = cafe24OrderApiPort.fetchOrders(
				from.atStartOfDay().format(f), to.atTime(23, 59, 59).format(f), 5, 0);
			return ResponseEntity.ok(Map.of("success", true, "orders", orders));
		} catch (Exception e) {
			log.error("Cafe24 주문 프리뷰 실패", e);
			Throwable cur = e;
			while (cur.getCause() != null && cur.getCause() != cur) {
				cur = cur.getCause();
			}
			return ResponseEntity.internalServerError().body(Map.of("success", false,
				"message", e.getMessage(), "rootCause", String.valueOf(cur.getMessage())));
		}
	}

	// 쿠팡 정산 데이터 동기화 POST 엔드포인트 매핑
	@PostMapping("/coupang/settlement")
	public ResponseEntity<Map<String, Object>> syncCoupangSettlement() {
		log.info("쿠팡 정산 데이터 동기화 요청 수신됨");

		try {
			coupangOrderSyncService.syncCoupangSettlement();
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "쿠팡 정산 데이터 동기화가 백그라운드에서 시작되었습니다."));
		} catch (Exception e) {
			log.error("쿠팡 정산 동기화 실패", e);
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "쿠팡 정산 동기화 실패: " + e.getMessage()));
		}
	}

	// 통관 상태 동기화 POST 엔드포인트 매핑
	@PostMapping("/customs")
	public ResponseEntity<Map<String, Object>> syncCustomsOrders() {
		log.info("통관 상태 동기화 요청 수신됨");
		try {
			// 통관 상태 동기화 호출
			customsOrderSyncService.syncCustomsStatus();
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "통관 상태 동기화가 완료되었습니다."));
		} catch (Exception e) {
			log.error("통관 상태 동기화 실패", e);
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "통관 상태 동기화 실패: " + e.getMessage()));
		}
	}

	// 동기화 상태 조회 엔드포인트
	@GetMapping("/status")
	public ResponseEntity<Map<String, com.sbshop.agent.core.application.sync.SyncStatusService.SyncStatus>> getSyncStatus() {
		return ResponseEntity.ok(syncStatusService.getAllStatuses());
	}

	// ESM+ 로그인 테스트 엔드포인트
	@PostMapping("/esmplus/test")
	public ResponseEntity<Map<String, Object>> testEsmplusLogin(@RequestBody
	Map<String, String> request) {
		log.info("ESM+ 로그인 테스트 요청 수신됨");

		try {
			String masterId = request.getOrDefault("masterId", System.getenv().getOrDefault("ESMPLUS_USER_ID", ""));
			String password = request.getOrDefault("password", System.getenv().getOrDefault("ESMPLUS_PASSWORD", ""));
			String fromDate = request.getOrDefault("fromDate", "2024-06-01");
			String toDate = request.getOrDefault("toDate", "2024-06-14");

			// Selenium으로 로그인 후 주문 스크래핑
			Map<String, Object> result = esmplusScraper.loginAndScrapeOrders(masterId, password, fromDate, toDate);

			return ResponseEntity.ok(result);
		} catch (Exception e) {
			log.error("ESM+ 로그인 테스트 실패", e);
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "ESM+ 로그인 테스트 실패: " + e.getMessage()));
		}
	}

	// ESM+ 주문 스크래핑 테스트 엔드포인트
	@PostMapping("/esmplus/scrape")
	public ResponseEntity<Map<String, Object>> testEsmplusScrape(@RequestBody
	Map<String, String> request) {
		log.info("ESM+ 주문 스크래핑 테스트 요청 수신됨");

		try {
			String masterId = request.getOrDefault("masterId", System.getenv().getOrDefault("ESMPLUS_USER_ID", ""));
			String password = request.getOrDefault("password", System.getenv().getOrDefault("ESMPLUS_PASSWORD", ""));
			String fromDate = request.getOrDefault("fromDate", "2024-06-01");
			String toDate = request.getOrDefault("toDate", "2024-06-14");

			// 로그인 후 주문 스크래핑 (같은 세션)
			Map<String, Object> result = esmplusScraper.loginAndScrapeOrders(masterId, password, fromDate, toDate);

			return ResponseEntity.ok(result);
		} catch (Exception e) {
			log.error("ESM+ 주문 스크래핑 테스트 실패", e);
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "ESM+ 주문 스크래핑 테스트 실패: " + e.getMessage()));
		}
	}
}
