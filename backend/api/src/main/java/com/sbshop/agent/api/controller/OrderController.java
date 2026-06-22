package com.sbshop.agent.api.controller;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbshop.agent.api.dto.OrderLineItemUpdateRequest;
import com.sbshop.agent.api.dto.OrderShipRequest;
import com.sbshop.agent.api.dto.OrderUpdateRequest;
import com.sbshop.agent.api.dto.ShippingUpdateRequest;
import com.sbshop.agent.api.dto.SourcingUpdateRequest;
import com.sbshop.agent.core.application.order.dto.BulkConfirmResult;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;
import com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand;
import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import com.sbshop.agent.core.application.order.service.OrderService;
import com.sbshop.agent.core.application.order.service.OrderShipService;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

	/** PATCH /orders/{id} - 주소/통관번호 사용자 수정 @reviewed */
	@PatchMapping("/{id}")
	public ResponseEntity<Order> updateOrder(
		@PathVariable
		Long id, @RequestBody
		OrderUpdateRequest request) {
		OrderUpdateCommand command = OrderUpdateCommand.builder()
			.address(request.getAddress())
			.customsClearanceNo(request.getCustomsClearanceNo())
			.build();

		Order updated = orderService.updateOrder(id, command);

		return ResponseEntity.ok(updated);
	}

	/** PATCH /line-items/{lineItemId} - 유니패스완료여부 사용자 수정 @reviewed */
	@PatchMapping("/line-items/{lineItemId}")
	public ResponseEntity<OrderLineItem> updateOrderLineItem(
		@PathVariable
		Long lineItemId,
		@RequestBody
		OrderLineItemUpdateRequest request) {
		OrderLineItemUpdateCommand command = OrderLineItemUpdateCommand.builder()
			.isUnipassDone(request.getIsUnipassDone())
			.build();

		OrderLineItem updated = orderService.updateOrderLineItem(lineItemId, command);

		return ResponseEntity.ok(updated);
	}

	/** PATCH /line-items/{lineItemId}/sourcing - 구매(소싱) 정보 수정 @reviewed */
	@PatchMapping("/line-items/{lineItemId}/sourcing")
	public ResponseEntity<OrderLineItem> updateSourcingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		SourcingUpdateRequest request) {
		OrderLineItem updated = orderService.updateSourcingInfo(lineItemId, request.toCommand());
		return ResponseEntity.ok(updated);
	}

	/** PATCH /line-items/{lineItemId}/shipping - 배송 정보 수정 @reviewed */
	@PatchMapping("/line-items/{lineItemId}/shipping")
	public ResponseEntity<OrderLineItem> updateShippingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		ShippingUpdateRequest request) {
		OrderLineItem updated = orderService.updateShippingInfo(lineItemId, request.toCommand());
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
	public ResponseEntity<List<Order>> shipOrders(@RequestBody
	OrderShipRequest request) {
		List<Order> shippedOrders = orderShipService.bulkShipOrders(request.getOrderIds());

		return ResponseEntity.ok(shippedOrders);
	}

	@PostMapping("/{id}/confirm")
	public ResponseEntity<Order> confirmOrder(@PathVariable
	Long id) {
		Order order = orderService.confirmOrder(id);
		return ResponseEntity.ok(order);
	}

	@PostMapping("/confirm/batch")
	public ResponseEntity<BulkConfirmResult> bulkConfirmOrders(
		@RequestBody
		Map<String, List<Long>> request) {
		List<Long> orderIds = request.get("orderIds");
		if (orderIds == null || orderIds.isEmpty()) {
			return ResponseEntity.badRequest().build();
		}
		BulkConfirmResult result = orderService.bulkConfirmOrders(orderIds);
		return ResponseEntity.ok(result);
	}

	@PostMapping("/{id}/cancel")
	public ResponseEntity<Order> cancelOrder(@PathVariable
	Long id) {
		Order order = orderService.cancelOrder(id);
		return ResponseEntity.ok(order);
	}
}
