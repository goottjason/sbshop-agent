package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.config.InternalAccessGuard;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductSyncController {

	private final ProductSyncService productSyncService;
	private final ActionLogService actionLogService;
	private final InternalAccessGuard internalAccessGuard;

	@PostMapping("/sync/stock")
	public ResponseEntity<Map<String, Object>> syncAllProductStock(
		@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false)
		String internalToken) {
		if (!internalAccessGuard.isAllowed(internalToken)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.<String, Object>of("success", false, "message", "forbidden: invalid internal token"));
		}
		actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
			ActionStatus.STARTED, "재고 동기화 요청");
		try {
			productSyncService.syncStockForPreparingOrdersAsync();

			return ResponseEntity.ok(Map.<String, Object>of("success", true, "message",
				"NEW/PREPARING 상태 주문 상품 재고 동기화 시작"));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
				ActionStatus.FAILED, "재고 동기화 실패: " + e.getMessage());

			return ResponseEntity.internalServerError()
				.body(Map.<String, Object>of("success", false, "message", failureMessage(e)));
		}
	}

	private String failureMessage(Exception e) {
		return e.getMessage() != null ? e.getMessage() : "재고 동기화 실패";
	}
}
