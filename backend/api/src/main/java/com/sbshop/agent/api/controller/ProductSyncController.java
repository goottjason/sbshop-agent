package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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

	@PostMapping("/sync/stock")
	public ResponseEntity<?> syncAllProductStock() {
		// D-076: 재고 동기화(백그라운드 크롤) — 트리거 시점 기록(STARTED).
		actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
			ActionStatus.STARTED, "재고 동기화 요청");
		try {
			// F-MISC-8/9: 대상선정 + 크롤을 관리되는 @Async(syncTaskExecutor)로 위임.
			// 원시 new Thread(고갈·예외 유실)를 제거하고, 성공/실패는 서비스가 ActionLog로 기록한다.
			productSyncService.syncStockForPreparingOrdersAsync();

			// F-MISC-10: 응답 메시지를 실제 동작(NEW/PREPARING 대상)에 맞게 정정.
			return ResponseEntity.ok(Map.of("success", true, "message",
				"NEW/PREPARING 상태 주문 상품 재고 동기화 시작"));
		} catch (Exception e) {
			// @Async 디스패치 실패 등 예외 시 에러 응답 반환
			return ResponseEntity.internalServerError()
				.body(Map.of("success", false, "message", e.getMessage()));
		}
	}
}
