package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.OrderIdsRequest;
import com.sbshop.agent.api.dto.OrderLineItemResponse;
import com.sbshop.agent.api.dto.OrderLineItemUpdateRequest;
import com.sbshop.agent.api.dto.OrderResponse;
import com.sbshop.agent.api.dto.OrderShipRequest;
import com.sbshop.agent.api.dto.OrderUpdateRequest;
import com.sbshop.agent.api.dto.ShippingUpdateRequest;
import com.sbshop.agent.api.dto.SourcingUpdateRequest;
import com.sbshop.agent.api.dto.UpdatePurchaseStatusRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.dto.BulkConfirmResult;
import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;
import com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand;
import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import com.sbshop.agent.core.application.order.service.OrderService;
import com.sbshop.agent.core.application.order.service.OrderShipService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OrderController {
	private final OrderService orderService;
	private final OrderShipService orderShipService;
	private final ActionLogService actionLogService;

	@GetMapping
	public ResponseEntity<Page<OrderDetailDto>> getOrders(
		OrderSearchCondition condition, Pageable pageable) {

		Page<OrderDetailDto> dtoPage = orderService.searchOrders(condition, pageable);
		return ResponseEntity.ok(dtoPage);
	}

	@PostMapping("/{id}/confirm")
	public ResponseEntity<OrderResponse> confirmOrder(@PathVariable
	Long id) {

		try {
			Order order = orderService.confirmOrder(id);
			actionLogService.record(ActionLogConstants.ORDER_CONFIRM, marketOf(order),
				ActionStatus.SUCCESS, "발주확인 성공 (주문 " + id + ")");
			return ResponseEntity.ok(OrderResponse.from(order));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.ORDER_CONFIRM, marketNameOfOrder(id),
				ActionStatus.FAILED, "발주확인 실패 (주문 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@PostMapping("/confirm/batch")
	public ResponseEntity<BulkConfirmResult> bulkConfirmOrders(
		@RequestBody
		OrderIdsRequest request) {

		List<Long> orderIds = request.orderIds();
		if (orderIds == null || orderIds.isEmpty()) {
			return ResponseEntity.badRequest().build();
		}

		try {
			BulkConfirmResult result = orderService.bulkConfirmOrders(orderIds);
			actionLogService.record(ActionLogConstants.ORDER_CONFIRM_BATCH, null,
				statusOf(result.getFailedCount()),
				"일괄 발주확인 (성공 " + result.getSuccessCount() + "건 / 실패 " + result.getFailedCount() + "건)");
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.ORDER_CONFIRM_BATCH, null,
				ActionStatus.FAILED, "일괄 발주확인 실패 (" + orderIds.size() + "건): " + e.getMessage());
			throw e;
		}
	}

	@PostMapping("/{id}/cancel")
	public ResponseEntity<OrderResponse> cancelOrder(@PathVariable
	Long id) {

		try {
			Order order = orderService.cancelOrder(id);
			actionLogService.record(ActionLogConstants.ORDER_CANCEL, marketOf(order),
				ActionStatus.SUCCESS, "발주취소 성공 (주문 " + id + ")");
			return ResponseEntity.ok(OrderResponse.from(order));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.ORDER_CANCEL, marketNameOfOrder(id),
				ActionStatus.FAILED, "발주취소 실패 (주문 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@PostMapping("/cancel/batch")
	public ResponseEntity<BulkConfirmResult> bulkCancelOrders(
		@RequestBody
		OrderIdsRequest request) {

		List<Long> orderIds = request.orderIds();
		if (orderIds == null || orderIds.isEmpty()) {
			return ResponseEntity.badRequest().build();
		}

		try {
			BulkConfirmResult result = orderService.bulkCancelOrders(orderIds);
			actionLogService.record(ActionLogConstants.ORDER_CANCEL_BATCH, null,
				statusOf(result.getFailedCount()),
				"일괄 발주취소 (성공 " + result.getSuccessCount() + "건 / 실패 " + result.getFailedCount() + "건)");
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.ORDER_CANCEL_BATCH, null,
				ActionStatus.FAILED, "일괄 발주취소 실패 (" + orderIds.size() + "건): " + e.getMessage());
			throw e;
		}
	}

	@PatchMapping("/{id}")
	public ResponseEntity<OrderResponse> updateOrder(
		@PathVariable
		Long id,
		@RequestBody
		OrderUpdateRequest request) {

		OrderUpdateCommand command = request.toCommand();
		try {
			Order updated = orderService.updateOrder(id, command);
			actionLogService.record(ActionLogConstants.ORDER_UPDATE, marketOf(updated),
				ActionStatus.SUCCESS, "주문정보 수정 성공 (주문 " + id + ")");
			return ResponseEntity.ok(OrderResponse.from(updated));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.ORDER_UPDATE, null,
				ActionStatus.FAILED, "주문정보 수정 실패 (주문 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@PatchMapping("/line-items/{lineItemId}")
	public ResponseEntity<OrderLineItemResponse> updateOrderLineItem(
		@PathVariable
		Long lineItemId,
		@RequestBody
		OrderLineItemUpdateRequest request) {

		OrderLineItemUpdateCommand command = request.toCommand();
		try {
			OrderLineItem updated = orderService.updateOrderLineItem(lineItemId, command);
			actionLogService.record(ActionLogConstants.UNIPASS_UPDATE, marketNameOfLineItem(lineItemId),
				ActionStatus.SUCCESS, "유니패스 수정 성공 (품목 " + lineItemId + ")");
			return ResponseEntity.ok(OrderLineItemResponse.from(updated));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.UNIPASS_UPDATE, null,
				ActionStatus.FAILED, "유니패스 수정 실패 (품목 " + lineItemId + "): " + e.getMessage());
			throw e;
		}
	}

	@PatchMapping("/line-items/{lineItemId}/sourcing")
	public ResponseEntity<OrderLineItemResponse> updateSourcingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		SourcingUpdateRequest request) {

		validateSourcingAmounts(request);
		try {
			OrderLineItem updated = orderService.updateSourcingInfo(lineItemId, request.toCommand());
			actionLogService.record(ActionLogConstants.PURCHASE_UPDATE, marketNameOfLineItem(lineItemId),
				ActionStatus.SUCCESS, "구매정보 수정 성공 (품목 " + lineItemId + ")");
			return ResponseEntity.ok(OrderLineItemResponse.from(updated));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PURCHASE_UPDATE, null,
				ActionStatus.FAILED, "구매정보 수정 실패 (품목 " + lineItemId + "): " + e.getMessage());
			throw e;
		}
	}

	@PatchMapping("/line-items/{lineItemId}/shipping")
	public ResponseEntity<OrderLineItemResponse> updateShippingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		ShippingUpdateRequest request) {

		try {
			OrderLineItem updated = orderService.updateShippingInfo(lineItemId, request.toCommand());
			actionLogService.record(ActionLogConstants.SHIPPING_UPDATE, marketNameOfLineItem(lineItemId),
				ActionStatus.SUCCESS, "배송정보 수정 성공 (품목 " + lineItemId + ")");
			return ResponseEntity.ok(OrderLineItemResponse.from(updated));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.SHIPPING_UPDATE, null,
				ActionStatus.FAILED, "배송정보 수정 실패 (품목 " + lineItemId + "): " + e.getMessage());
			throw e;
		}
	}

	@PatchMapping("/line-items/{lineItemId}/purchase-status")
	public ResponseEntity<OrderLineItemResponse> updatePurchaseStatus(
		@PathVariable
		Long lineItemId,
		@RequestBody
		UpdatePurchaseStatusRequest request) {

		try {
			OrderLineItem updated = orderService.updatePurchaseStatus(lineItemId, request.getPurchaseStatus());
			actionLogService.record(ActionLogConstants.PURCHASE_UPDATE, marketNameOfLineItem(lineItemId),
				ActionStatus.SUCCESS, "구매상태 수정 성공 (품목 " + lineItemId + ")");
			return ResponseEntity.ok(OrderLineItemResponse.from(updated));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PURCHASE_UPDATE, null,
				ActionStatus.FAILED, "구매상태 수정 실패 (품목 " + lineItemId + "): " + e.getMessage());
			throw e;
		}
	}

	@PostMapping("/ship")
	public ResponseEntity<BulkShipResult> shipOrders(@RequestBody
	OrderShipRequest request) {

		int reqCount = request.getOrderIds() != null ? request.getOrderIds().size() : 0;
		try {
			BulkShipResult result = orderShipService.bulkShipOrders(request.getOrderIds());
			actionLogService.record(ActionLogConstants.ORDER_SHIP, null,
				statusOf(result.getFailedCount()),
				"발송 처리 (성공 " + result.getSuccessCount() + "건 / 실패 " + result.getFailedCount()
					+ "건 / 스킵 " + result.getSkippedCount() + "건)");
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.ORDER_SHIP, null,
				ActionStatus.FAILED, "발송 처리 실패 (" + reqCount + "건): " + e.getMessage());
			throw e;
		}
	}

	private static String marketOf(Order order) {
		return order != null && order.getMarketType() != null ? order.getMarketType().name() : null;
	}

	private static String nameOf(MarketType type) {
		return type != null ? type.name() : null;
	}

	private String marketNameOfOrder(Long id) {
		return nameOf(orderService.marketTypeOfOrder(id));
	}

	private static ActionStatus statusOf(int failedCount) {
		return failedCount == 0 ? ActionStatus.SUCCESS : ActionStatus.FAILED;
	}

	private String marketNameOfLineItem(Long lineItemId) {
		return nameOf(orderService.marketTypeOfLineItem(lineItemId));
	}

	private void validateSourcingAmounts(SourcingUpdateRequest request) {
		requireNonNegative("소싱금액(sourcingAmount)", request.getSourcingAmount());
		requireNonNegative("물류비(logisticsCost)", request.getLogisticsCost());
	}

	private void requireNonNegative(String label, BigDecimal value) {
		if (value != null && value.signum() < 0) {
			throw new IllegalArgumentException(label + "는 0 이상이어야 합니다: " + value);
		}
	}

}
