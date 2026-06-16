package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.OrderUpdateRequest;
import com.sbshop.agent.api.service.IherbEmailSearchService;
import com.sbshop.agent.core.application.order.service.OrderService;
import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.sbshop.agent.api.dto.OrderShipRequest;
import com.sbshop.agent.core.application.order.service.OrderShipService;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;

@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // For local Vite frontend
public class OrderController {

	// 주문 비즈니스 로직 서비스
	private final OrderService orderService;

	// 발송 처리 전용 서비스
	private final OrderShipService orderShipService;

	// iHerb 이메일 검색 서비스
	private final IherbEmailSearchService iherbEmailSearchService;

	@GetMapping
	public ResponseEntity<Page<OrderDetailDto>> getOrders(
		OrderSearchCondition condition, Pageable pageable) {
		System.out.println("DEBUG OrderSearchCondition: " + condition);
		// 1. 주문 검색 조건 및 페이징 파라미터 전달하여 조회
		Page<OrderDetailDto> dtoPage = orderService.searchOrders(condition,
			pageable);

		// 2. 조회된 그리드용 DTO 페이지 반환
		return ResponseEntity.ok(dtoPage);
	}

	@PatchMapping("/{id}")
	public ResponseEntity<Order> updateOrder(
		@PathVariable
		Long id, @RequestBody
		OrderUpdateRequest request) {
		// 1. HTTP 요청 객체에서 비즈니스 커맨드 객체로 매핑 (의존성 분리)
		OrderUpdateCommand command = OrderUpdateCommand.builder()
			.recipientName(request.getRecipientName())
			.recipientPhone(request.getRecipientPhone())
			.zipcode(request.getZipcode())
			.address(request.getAddress())
			.message(request.getMessage())
			.customsClearanceNo(request.getCustomsClearanceNo())
			.customsStatus(request.getCustomsStatus())
			.build();

		// 2. 서비스 레이어에 업데이트 명령 위임
		Order updated = orderService.updateOrder(id, command);

		// 3. 변경 완료된 엔티티 반환
		return ResponseEntity.ok(updated);
	}

	@PatchMapping("/line-items/{id}")
	public ResponseEntity<com.sbshop.agent.core.domain.order.OrderLineItem> updateOrderLineItem(
		@PathVariable
		Long id, @RequestBody
		com.sbshop.agent.api.dto.OrderLineItemUpdateRequest request) {

		com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand command = com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand
			.builder()
			.sourcingAccount(request.getSourcingAccount())
			.sourcingOrderNo(request.getSourcingOrderNo())
			.sourcingAmount(request.getSourcingAmount())
			.discountCode(request.getDiscountCode())
			.sourcingVendor(request.getSourcingVendor())
			.shippingFee(request.getShippingFee())
			.shippingCarrier(request.getShippingCarrier())
			.trackingNo(request.getTrackingNo())
			.shippingStatus(request.getShippingStatus())
			.isUnipassDone(request.getIsUnipassDone())
			.trackingSentToMarket(request.getTrackingSentToMarket())
			.settlementAmount(request.getSettlementAmount())
			.build();

		com.sbshop.agent.core.domain.order.OrderLineItem updated = orderService.updateOrderLineItem(id, command);
		return ResponseEntity.ok(updated);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteOrder(@PathVariable
	Long id) {
		// 1. 주문 ID를 통한 삭제 로직 호출
		orderService.deleteOrder(id);

		// 2. 삭제 완료 (No Content) 응답
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/ship")
	public ResponseEntity<java.util.Map<String, Object>> shipOrders(@RequestBody
	OrderShipRequest request) {
		// 1. 발송 처리 서비스 호출 (벌크 처리)
		int count = orderShipService.bulkShipOrders(request.getOrderIds());

		// 2. 처리 결과 맵 구성 및 반환 (성공 여부, 처리 건수)
		return ResponseEntity.ok(java.util.Map.of("success", true, "shippedCount", count, "message",
			"Successfully shipped " + count + " orders."));
	}

	@PostMapping("/{id}/confirm")
	public ResponseEntity<java.util.Map<String, Object>> confirmOrder(@PathVariable
	Long id) {
		orderService.confirmOrder(id);
		return ResponseEntity.ok(java.util.Map.of("success", true, "message", "Order confirmed successfully."));
	}

	@PostMapping("/confirm/batch")
	public ResponseEntity<java.util.Map<String, Object>> bulkConfirmOrders(
		@RequestBody
		java.util.Map<String, java.util.List<Long>> request) {
		java.util.List<Long> orderIds = request.get("orderIds");
		if (orderIds == null || orderIds.isEmpty()) {
			return ResponseEntity.badRequest()
				.body(java.util.Map.of("success", false, "message", "No order IDs provided."));
		}
		java.util.Map<String, Object> result = orderService.bulkConfirmOrders(orderIds);
		boolean allSuccess = (int)result.get("failedCount") == 0;
		return ResponseEntity.ok(java.util.Map.of("success", allSuccess, "result", result));
	}

	@PostMapping("/{id}/cancel")
	public ResponseEntity<java.util.Map<String, Object>> cancelOrder(@PathVariable
	Long id) {
		orderService.cancelOrder(id);
		return ResponseEntity.ok(java.util.Map.of("success", true, "message", "Order canceled successfully."));
	}

	// 구매 처리 (PREPARING → PURCHASED) - 즉시 응답
	@PostMapping("/line-items/{lineItemId}/purchase")
	public ResponseEntity<java.util.Map<String, Object>> markLineItemPurchased(
		@PathVariable
		Long lineItemId,
		@RequestBody
		java.util.Map<String, String> request) {
		String sourcingAccount = request.get("sourcingAccount");
		String sourcingOrderNo = request.get("sourcingOrderNo");
		String discountCode = request.get("discountCode");
		String sourcingVendor = request.get("sourcingVendor");

		// 즉시 구매 처리 (이메일 검색 없이)
		orderService.markAsPurchasedWithAmount(lineItemId, sourcingAccount, sourcingOrderNo, discountCode,
			sourcingVendor, null);

		// 아이허브 상품이면 백그라운드에서 이메일 검색 후 실구매가 업데이트
		if (sourcingAccount != null && !sourcingAccount.isEmpty()
			&& sourcingOrderNo != null && !sourcingOrderNo.isEmpty()) {
			String finalSourcingAccount = sourcingAccount;
			String finalSourcingOrderNo = sourcingOrderNo;
			new Thread(() -> {
				try {
					log.info("백그라운드 이메일 검색 시작: orderNo={}", finalSourcingOrderNo);
					java.math.BigDecimal emailAmount = iherbEmailSearchService
						.findConfirmationAmount(finalSourcingOrderNo)
						.orElse(null);
					if (emailAmount != null) {
						orderService.updateSourcingAmount(lineItemId, emailAmount);
						log.info("백그라운드 이메일 검색 완료: orderNo={}, amount={}", finalSourcingOrderNo, emailAmount);
					} else {
						log.info("백그라운드 이메일 검색: 결제금액 미발견 (스케줄러가 채움): orderNo={}", finalSourcingOrderNo);
					}
				} catch (Exception e) {
					log.warn("백그라운드 이메일 검색 실패: {}", e.getMessage());
				}
			}, "iherb-email-" + finalSourcingOrderNo).start();
		}

		return ResponseEntity.ok(java.util.Map.of("success", true, "message", "구매 완료 처리됨"));
	}

	// 배송 처리 (PURCHASED → SHIPPED)
	@PostMapping("/line-items/{lineItemId}/ship")
	public ResponseEntity<java.util.Map<String, Object>> processShipping(
		@PathVariable
		Long lineItemId,
		@RequestBody
		java.util.Map<String, String> request) {
		String trackingNo = request.get("trackingNo");
		String carrier = request.get("carrier");

		com.sbshop.agent.core.domain.order.enums.ShippingCarrier shippingCarrier = com.sbshop.agent.core.domain.order.enums.ShippingCarrier
			.valueOf(carrier);
		orderService.processShipping(lineItemId, trackingNo, shippingCarrier);
		return ResponseEntity.ok(java.util.Map.of("success", true, "message", "배송 처리 완료"));
	}

	// 송장 수정 (SHIPPED 상태)
	@PutMapping("/line-items/{lineItemId}/tracking")
	public ResponseEntity<java.util.Map<String, Object>> updateTrackingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		java.util.Map<String, String> request) {
		String trackingNo = request.get("trackingNo");
		String carrier = request.get("carrier");

		com.sbshop.agent.core.domain.order.enums.ShippingCarrier shippingCarrier = com.sbshop.agent.core.domain.order.enums.ShippingCarrier
			.valueOf(carrier);
		orderService.updateTrackingInfo(lineItemId, trackingNo, shippingCarrier);
		return ResponseEntity.ok(java.util.Map.of("success", true, "message", "송장 수정 완료"));
	}
}
