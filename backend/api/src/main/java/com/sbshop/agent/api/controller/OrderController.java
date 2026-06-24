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
@CrossOrigin(origins = "*")
public class OrderController {

	private final OrderService orderService;
	private final OrderShipService orderShipService;

	// ======================== 조회 ========================

	/** 주문 그리드 조회 */
	@GetMapping
	public ResponseEntity<Page<OrderDetailDto>> getOrders(
		OrderSearchCondition condition, Pageable pageable) {

		Page<OrderDetailDto> dtoPage = orderService.searchOrders(condition, pageable);
		return ResponseEntity.ok(dtoPage);
	}

	// ======================== 발주확인 ========================

	/** 단건 발주확인 */
	@PostMapping("/{id}/confirm")
	public ResponseEntity<Order> confirmOrder(@PathVariable
	Long id) {

		Order order = orderService.confirmOrder(id);
		return ResponseEntity.ok(order);
	}

	/** 선택(일괄) 발주확인 */
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

	// ======================== 발주취소 ========================

	/** 단건 발주취소 */
	@PostMapping("/{id}/cancel")
	public ResponseEntity<Order> cancelOrder(@PathVariable
	Long id) {

		Order order = orderService.cancelOrder(id);
		return ResponseEntity.ok(order);
	}

	/** 선택(일괄) 발주취소 */
	@PostMapping("/cancel/batch")
	public ResponseEntity<BulkConfirmResult> bulkCancelOrders(
		@RequestBody
		Map<String, List<Long>> request) {

		List<Long> orderIds = request.get("orderIds");
		if (orderIds == null || orderIds.isEmpty()) {
			return ResponseEntity.badRequest().build();
		}

		BulkConfirmResult result = orderService.bulkCancelOrders(orderIds);
		return ResponseEntity.ok(result);
	}

	// ======================== 수정 ========================

	/** 주소/통관번호 사용자 수정 */
	@PatchMapping("/{id}")
	public ResponseEntity<Order> updateOrder(
		@PathVariable
		Long id,
		@RequestBody
		OrderUpdateRequest request) {

		OrderUpdateCommand command = request.toCommand();
		Order updated = orderService.updateOrder(id, command);
		return ResponseEntity.ok(updated);
	}

	/** 유니패스완료여부 사용자 수정 */
	@PatchMapping("/line-items/{lineItemId}")
	public ResponseEntity<OrderLineItem> updateOrderLineItem(
		@PathVariable
		Long lineItemId,
		@RequestBody
		OrderLineItemUpdateRequest request) {

		OrderLineItemUpdateCommand command = request.toCommand();
		OrderLineItem updated = orderService.updateOrderLineItem(lineItemId, command);
		return ResponseEntity.ok(updated);
	}

	/** 라인아이템 소싱(구매) 정보 수정 */
	@PatchMapping("/line-items/{lineItemId}/sourcing")
	public ResponseEntity<OrderLineItem> updateSourcingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		SourcingUpdateRequest request) {

		OrderLineItem updated = orderService.updateSourcingInfo(lineItemId, request.toCommand());
		return ResponseEntity.ok(updated);
	}

	/** 라인아이템 배송 정보 수정 */
	@PatchMapping("/line-items/{lineItemId}/shipping")
	public ResponseEntity<OrderLineItem> updateShippingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		ShippingUpdateRequest request) {

		OrderLineItem updated = orderService.updateShippingInfo(lineItemId, request.toCommand());
		return ResponseEntity.ok(updated);
	}

	// ======================== 발송 ========================

	/** 일괄 발송 처리 */
	@PostMapping("/ship")
	public ResponseEntity<List<Order>> shipOrders(@RequestBody
	OrderShipRequest request) {

		List<Order> shippedOrders = orderShipService.bulkShipOrders(request.getOrderIds());
		return ResponseEntity.ok(shippedOrders);
	}

	// ======================== 삭제 ========================

	/** 주문 삭제 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteOrder(@PathVariable
	Long id) {

		orderService.deleteOrder(id);
		return ResponseEntity.noContent().build();
	}
}
