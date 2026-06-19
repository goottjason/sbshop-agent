package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.OrderUpdateRequest;
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

	// 구매(소싱) 정보 저장 및 수정 통합 API
	@PutMapping("/line-items/{lineItemId}/sourcing")
	public ResponseEntity<java.util.Map<String, Object>> saveSourcingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		java.util.Map<String, String> request) {
		String sourcingAccount = request.get("sourcingAccount");
		String sourcingOrderNo = request.get("sourcingOrderNo");
		String discountCode = request.get("discountCode");
		String sourcingVendor = request.get("sourcingVendor");

		// 상태에 따라 구매처리 또는 단순 정보업데이트 분기
		orderService.saveSourcingInfo(lineItemId, sourcingAccount, sourcingOrderNo, discountCode, sourcingVendor);

		return ResponseEntity.ok(java.util.Map.of("success", true, "message", "구매 정보가 저장되었습니다."));
	}

	// 배송 정보 저장 및 수정 통합 API
	@PutMapping("/line-items/{lineItemId}/shipping")
	public ResponseEntity<java.util.Map<String, Object>> saveShippingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		java.util.Map<String, String> request) {
		String trackingNo = request.get("trackingNo");
		String carrier = request.get("carrier");

		com.sbshop.agent.core.domain.order.enums.ShippingCarrier shippingCarrier = com.sbshop.agent.core.domain.order.enums.ShippingCarrier
			.valueOf(carrier);

		// 상태에 따라 배송처리 또는 단순 송장수정 분기
		orderService.saveShippingInfo(lineItemId, trackingNo, shippingCarrier);

		return ResponseEntity.ok(java.util.Map.of("success", true, "message", "배송 정보가 저장되었습니다."));
	}
}
