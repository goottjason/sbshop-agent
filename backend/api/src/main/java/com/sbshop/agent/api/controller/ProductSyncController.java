package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductSyncController {

	private final ProductSyncService productSyncService;
	private final OrderLineItemRepository orderLineItemRepository;
	// D-076: 사용자 액션 활동로그 기록 서비스
	private final ActionLogService actionLogService;

	@PostMapping("/sync/stock")
	public ResponseEntity<?> syncAllProductStock() {
		// D-076: 재고 동기화(백그라운드 크롤) — 시작 기록(STARTED).
		actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
			ActionStatus.STARTED, "재고 동기화 요청");
		try {
			// 1. 별도의 비동기 스레드 생성 (응답 지연 방지)
			new Thread(() -> {
				// 2. '결제완료(NEW)' 및 '배송준비(PREPARING)' 상태인 주문에 포함된 상품 ID 목록 조회
				List<Long> productIdsNew = orderLineItemRepository
					.findProductIdsByShippingStatus(ShippingStatus.NEW);
				List<Long> productIdsPreparing = orderLineItemRepository
					.findProductIdsByShippingStatus(ShippingStatus.PREPARING);

				// 중복 제거 후 합산
				Set<Long> mergedIds = new LinkedHashSet<>(productIdsNew);
				mergedIds.addAll(productIdsPreparing);

				// 3. 필터링된 상품 ID 목록만 동기화 서비스로 전달 (안전한 크롤링)
				productSyncService.syncStockForPreparingOrders(new ArrayList<>(mergedIds));
			}).start();

			// 4. 백그라운드 작업 시작 성공 응답 반환
			return ResponseEntity.ok(Map.of("success", true, "message",
				"Targeted stock sync for PREPARING orders started in background"));
		} catch (Exception e) {
			// 5. 스레드 생성 등 예외 발생 시 에러 응답 반환
			return ResponseEntity.internalServerError()
				.body(Map.of("success", false, "message", e.getMessage()));
		}
	}
}
