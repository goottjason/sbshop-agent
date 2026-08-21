package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.sync.SyncStatusResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.common.RootCauseExtractor;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/orders/sync")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OrderSyncController {
	private final CoupangOrderSyncService coupangOrderSyncService;
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;
	private final ElevenstOrderSyncService elevenstOrderSyncService;
	private final Cafe24OrderSyncService cafe24OrderSyncService;
	private final Cafe24OrderApiPort cafe24OrderApiPort;
	private final CustomsOrderSyncService customsOrderSyncService;
	private final SyncStatusService syncStatusService;
	private final ActionLogService actionLogService;

	@PostMapping("/coupang")
	public ResponseEntity<Map<String, Object>> syncCoupangOrders() {
		log.info("쿠팡 주문 동기화 요청 수신됨");
		actionLogService.record("COUPANG_SYNC", "COUPANG", ActionStatus.STARTED, "쿠팡 동기화 요청");

		try {
			coupangOrderSyncService.syncCoupangOrders();
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "쿠팡 주문 동기화가 백그라운드에서 시작되었습니다."));
		} catch (Exception e) {
			log.error("쿠팡 주문 동기화 실패", e);
			actionLogService.record("COUPANG_SYNC", "COUPANG", ActionStatus.FAILED,
				"쿠팡 동기화 실패: " + e.getMessage());
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "쿠팡 주문 동기화 실패: " + e.getMessage()));
		}
	}

	@PostMapping("/smartstore")
	public ResponseEntity<Map<String, Object>> syncSmartStoreOrders() {
		log.info("스마트스토어 주문 동기화 요청 수신됨");
		actionLogService.record("SMART_STORE_SYNC", "SMART_STORE", ActionStatus.STARTED,
			"스마트스토어 동기화 요청");

		try {
			smartStoreOrderSyncService.syncSmartStoreOrders();
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "스마트스토어 주문 동기화가 백그라운드에서 시작되었습니다."));
		} catch (Exception e) {
			log.error("스마트스토어 주문 동기화 실패", e);
			actionLogService.record("SMART_STORE_SYNC", "SMART_STORE", ActionStatus.FAILED,
				"스마트스토어 동기화 실패: " + e.getMessage());
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "스마트스토어 주문 동기화 실패: " + e.getMessage()));
		}
	}

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
			actionLogService.record("ELEVEN_STREET_SYNC", "ELEVEN_STREET", ActionStatus.FAILED,
				"11번가 동기화 실패: " + e.getMessage());
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "11번가 주문 동기화 실패: " + e.getMessage()));
		}
	}

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
			actionLogService.record("GMARKET_SYNC", "GMARKET", ActionStatus.FAILED,
				"G마켓/옥션(Cafe24) 동기화 실패: " + e.getMessage());
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "G마켓/옥션 주문 동기화 실패: " + e.getMessage()));
		}
	}

	@PostMapping("/coupang/settlement")
	public ResponseEntity<Map<String, Object>> syncCoupangSettlement() {
		log.info("쿠팡 정산 데이터 동기화 요청 수신됨");
		actionLogService.record(ActionLogConstants.COUPANG_SETTLEMENT_SYNC, "COUPANG",
			ActionStatus.STARTED, "쿠팡 정산 동기화 요청");

		try {
			coupangOrderSyncService.syncCoupangSettlement();
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "쿠팡 정산 데이터 동기화가 백그라운드에서 시작되었습니다."));
		} catch (Exception e) {
			log.error("쿠팡 정산 동기화 실패", e);
			actionLogService.record(ActionLogConstants.COUPANG_SETTLEMENT_SYNC, "COUPANG",
				ActionStatus.FAILED, "쿠팡 정산 동기화 실패: " + e.getMessage());
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "쿠팡 정산 동기화 실패: " + e.getMessage()));
		}
	}

	@PostMapping("/customs")
	public ResponseEntity<Map<String, Object>> syncCustomsOrders() {
		log.info("통관 상태 동기화 요청 수신됨");
		actionLogService.record(ActionLogConstants.CUSTOMS_SYNC, null,
			ActionStatus.STARTED, "통관 상태 동기화 요청");
		try {
			customsOrderSyncService.syncCustomsStatus();
			actionLogService.record(ActionLogConstants.CUSTOMS_SYNC, null,
				ActionStatus.SUCCESS, "통관 상태 동기화 완료");
			return ResponseEntity.ok(Map.of(
				"success", true,
				"message", "통관 상태 동기화가 완료되었습니다."));
		} catch (Exception e) {
			log.error("통관 상태 동기화 실패", e);
			actionLogService.record(ActionLogConstants.CUSTOMS_SYNC, null,
				ActionStatus.FAILED, "통관 상태 동기화 실패: " + e.getMessage());
			return ResponseEntity.internalServerError().body(Map.of(
				"success", false,
				"message", "통관 상태 동기화 실패: " + e.getMessage()));
		}
	}

	@PostMapping("/cafe24/preview")
	public ResponseEntity<Map<String, Object>> previewCafe24Orders() {
		try {
			LocalDate to = LocalDate.now();
			LocalDate from = to.minusDays(7);
			DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd");
			var orders = cafe24OrderApiPort.fetchOrders(from.format(f), to.format(f), 5, 0);
			return ResponseEntity.ok(Map.of("success", true, "orders", orders));
		} catch (Exception e) {
			log.error("Cafe24 주문 프리뷰 실패", e);
			return ResponseEntity.internalServerError().body(Map.of("success", false,
				"message", e.getMessage(), "rootCause", String.valueOf(RootCauseExtractor.rootMessage(e))));
		}
	}

	@PostMapping("/cafe24/carriers")
	public ResponseEntity<Map<String, Object>> previewCafe24Carriers() {
		try {
			return ResponseEntity.ok(Map.of("success", true, "carriers", cafe24OrderApiPort.fetchCarriers()));
		} catch (Exception e) {
			log.error("Cafe24 주문 프리뷰 실패", e);
			return ResponseEntity.internalServerError().body(Map.of("success", false,
				"message", e.getMessage(), "rootCause", String.valueOf(RootCauseExtractor.rootMessage(e))));
		}
	}

	@GetMapping("/status")
	public ResponseEntity<Map<String, SyncStatusResponse>> getSyncStatus() {
		Map<String, SyncStatusResponse> result = new LinkedHashMap<>();
		syncStatusService.getAllStatuses()
			.forEach((market, status) -> result.put(market, SyncStatusResponse.from(status)));
		return ResponseEntity.ok(result);
	}

}
