package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.config.InternalAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductSyncController {

	private final ProductSyncService productSyncService;
	// D-076: 사용자 액션 활동로그 기록 서비스
	private final ActionLogService actionLogService;
	// F-MISC-7: 공유시크릿 헤더 가드. INTERNAL_API_TOKEN 미설정 시 비활성(무파손, 프론트 옵트인).
	private final InternalAccessGuard internalAccessGuard;

	@PostMapping("/sync/stock")
	public ResponseEntity<Map<String, Object>> syncAllProductStock(
			@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false) String internalToken) {
		// F-MISC-7: 가드 활성 시 시크릿 헤더 불일치/누락은 동기화 트리거 전 403으로 차단.
		if (!internalAccessGuard.isAllowed(internalToken)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.<String, Object>of("success", false, "message", "forbidden: invalid internal token"));
		}
		// D-076: 재고 동기화(백그라운드 크롤) — 트리거 시점 기록(STARTED).
		actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
			ActionStatus.STARTED, "재고 동기화 요청");
		try {
			// F-MISC-8/9: 대상선정 + 크롤을 관리되는 @Async(syncTaskExecutor)로 위임.
			// 원시 new Thread(고갈·예외 유실)를 제거하고, 성공/실패는 서비스가 ActionLog로 기록한다.
			productSyncService.syncStockForPreparingOrdersAsync();

			// F-MISC-10: 응답 메시지를 실제 동작(NEW/PREPARING 대상)에 맞게 정정.
			return ResponseEntity.ok(Map.<String, Object>of("success", true, "message",
				"NEW/PREPARING 상태 주문 상품 재고 동기화 시작"));
		} catch (Exception e) {
			// @Async 디스패치 실패 등 예외 시 에러 응답 반환
			return ResponseEntity.internalServerError()
				.body(Map.<String, Object>of("success", false, "message", e.getMessage()));
		}
	}
}
