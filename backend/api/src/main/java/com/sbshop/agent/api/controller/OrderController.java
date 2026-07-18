package com.sbshop.agent.api.controller;

import java.util.List;

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

import com.sbshop.agent.api.dto.OrderIdsRequest;
import com.sbshop.agent.api.dto.OrderLineItemResponse;
import com.sbshop.agent.api.dto.OrderLineItemUpdateRequest;
import com.sbshop.agent.api.dto.OrderResponse;
import com.sbshop.agent.api.dto.OrderShipRequest;
import com.sbshop.agent.api.dto.OrderUpdateRequest;
import com.sbshop.agent.api.dto.ShippingUpdateRequest;
import com.sbshop.agent.api.dto.SourcingUpdateRequest;
import com.sbshop.agent.api.dto.UpdatePurchaseStatusRequest;
import com.sbshop.agent.core.application.order.dto.BulkConfirmResult;
import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;
import com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand;
import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import com.sbshop.agent.core.application.order.service.OrderService;
import com.sbshop.agent.core.application.order.service.OrderShipService;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
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
	// D-076: 사용자 액션 활동로그 기록 서비스
	private final ActionLogService actionLogService;

	/** Order의 marketType을 로그용 문자열로(없으면 null). */
	private static String marketOf(Order order) {
		return order != null && order.getMarketType() != null ? order.getMarketType().name() : null;
	}

	/** MarketType을 로그용 문자열로(없으면 null). */
	private static String nameOf(com.sbshop.agent.core.domain.order.enums.MarketType type) {
		return type != null ? type.name() : null;
	}

	/** 라인아이템이 속한 주문의 마켓 타입을 로그용 문자열로 해석(없으면 null). SP-6. */
	private String marketNameOfLineItem(Long lineItemId) {
		return nameOf(orderService.marketTypeOfLineItem(lineItemId));
	}

	/** 주문의 마켓 타입을 로그용 문자열로 해석(조회 실패 시 null). F-ORD-5/F-ORD-15 실패 경로용. */
	private String marketNameOfOrder(Long id) {
		return nameOf(orderService.marketTypeOfOrder(id));
	}

	/**
	 * 일괄 처리 결과를 활동로그 상태로 매핑한다(SP-3).
	 * 실패가 하나라도 있으면 FAILED, 전건 성공이면 SUCCESS. 부분성공도 FAILED로 표면화한다
	 * (성공N/실패M 요지는 메시지에 별도 기재).
	 */
	private static ActionStatus statusOf(int failedCount) {
		return failedCount == 0 ? ActionStatus.SUCCESS : ActionStatus.FAILED;
	}

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
	public ResponseEntity<OrderResponse> confirmOrder(@PathVariable
	Long id) {

		// D-076: 단건 발주확인 — 결과만 기록(SUCCESS/FAILED). 실패 시 재throw로 기존 에러 응답 보존.
		try {
			Order order = orderService.confirmOrder(id);
			actionLogService.record(ActionLogConstants.ORDER_CONFIRM, marketOf(order),
				ActionStatus.SUCCESS, "발주확인 성공 (주문 " + id + ")");
			return ResponseEntity.ok(OrderResponse.from(order));
		} catch (Exception e) {
			// F-ORD-5: 실패 경로도 주문 조회로 marketType을 채운다(조회 실패 시에만 null 유지).
			actionLogService.record(ActionLogConstants.ORDER_CONFIRM, marketNameOfOrder(id),
				ActionStatus.FAILED, "발주확인 실패 (주문 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	/** 선택(일괄) 발주확인 */
	@PostMapping("/confirm/batch")
	public ResponseEntity<BulkConfirmResult> bulkConfirmOrders(
		@RequestBody
		OrderIdsRequest request) {

		List<Long> orderIds = request.orderIds();
		if (orderIds == null || orderIds.isEmpty()) {
			return ResponseEntity.badRequest().build();
		}

		// D-076/SP-3: 일괄 발주확인 — 결과 기반으로 상태 기록(전건 실패·부분실패를 SUCCESS로 남기지 않음, F-ORD-9).
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

	// ======================== 발주취소 ========================

	/** 단건 발주취소 */
	@PostMapping("/{id}/cancel")
	public ResponseEntity<OrderResponse> cancelOrder(@PathVariable
	Long id) {

		// D-076: 단건 발주취소 — 결과만 기록.
		try {
			Order order = orderService.cancelOrder(id);
			actionLogService.record(ActionLogConstants.ORDER_CANCEL, marketOf(order),
				ActionStatus.SUCCESS, "발주취소 성공 (주문 " + id + ")");
			return ResponseEntity.ok(OrderResponse.from(order));
		} catch (Exception e) {
			// F-ORD-15: 실패 경로도 주문 조회로 marketType을 채운다(조회 실패 시에만 null 유지).
			actionLogService.record(ActionLogConstants.ORDER_CANCEL, marketNameOfOrder(id),
				ActionStatus.FAILED, "발주취소 실패 (주문 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	/** 선택(일괄) 발주취소 */
	@PostMapping("/cancel/batch")
	public ResponseEntity<BulkConfirmResult> bulkCancelOrders(
		@RequestBody
		OrderIdsRequest request) {

		List<Long> orderIds = request.orderIds();
		if (orderIds == null || orderIds.isEmpty()) {
			return ResponseEntity.badRequest().build();
		}

		// D-076/SP-3: 일괄 발주취소 — 결과 기반으로 상태 기록(전건 실패·부분실패를 SUCCESS로 남기지 않음, F-ORD-17).
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

	// ======================== 수정 ========================

	/** 주소/통관번호 사용자 수정 */
	@PatchMapping("/{id}")
	public ResponseEntity<OrderResponse> updateOrder(
		@PathVariable
		Long id,
		@RequestBody
		OrderUpdateRequest request) {

		OrderUpdateCommand command = request.toCommand();
		// D-076: 주소/통관번호 수정 — 결과만 기록.
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

	/** 유니패스완료여부 사용자 수정 */
	@PatchMapping("/line-items/{lineItemId}")
	public ResponseEntity<OrderLineItemResponse> updateOrderLineItem(
		@PathVariable
		Long lineItemId,
		@RequestBody
		OrderLineItemUpdateRequest request) {

		OrderLineItemUpdateCommand command = request.toCommand();
		// D-076/SP-6: 유니패스 완료여부 수정 — 성공 시 라인아이템 마켓을 해석해 기록(F-ORD-27).
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

	/** 라인아이템 소싱(구매) 정보 수정 */
	@PatchMapping("/line-items/{lineItemId}/sourcing")
	public ResponseEntity<OrderLineItemResponse> updateSourcingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		SourcingUpdateRequest request) {

		// R6/F-S4: 소싱 금액 필드 음수 검증 부재 → 진입부에서 400으로 거부.
		// 음수 금액이 마켓/정산 데이터로 전파되지 않도록 차단한다(F-PROD-8/23·F-PSRC-11 signum()<0 패턴).
		validateSourcingAmounts(request);
		// D-076/SP-6: 소싱(구매) 정보 수정 — 성공 시 라인아이템 마켓을 해석해 기록(F-S6).
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

	/**
	 * R6/F-S4: 소싱(구매) 수정 요청의 금액 필드가 음수가 아닌지 검증한다.
	 * null(미변경)·0(무상 소싱/무물류비)은 정상값으로 통과시켜 과잉거부하지 않는다.
	 * 음수는 잘못된 입력이므로 {@link IllegalArgumentException}으로 던져 400으로 매핑한다.
	 */
	private void validateSourcingAmounts(SourcingUpdateRequest request) {
		requireNonNegative("소싱금액(sourcingAmount)", request.getSourcingAmount());
		requireNonNegative("물류비(logisticsCost)", request.getLogisticsCost());
	}

	private void requireNonNegative(String label, java.math.BigDecimal value) {
		if (value != null && value.signum() < 0) {
			throw new IllegalArgumentException(label + "는 0 이상이어야 합니다: " + value);
		}
	}

	/** 라인아이템 배송 정보 수정 */
	@PatchMapping("/line-items/{lineItemId}/shipping")
	public ResponseEntity<OrderLineItemResponse> updateShippingInfo(
		@PathVariable
		Long lineItemId,
		@RequestBody
		ShippingUpdateRequest request) {

		// D-076/SP-6: 배송(송장) 정보 수정 — 성공 시 라인아이템 마켓을 해석해 기록(F-H6).
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

	/** 라인아이템 구매 상태 수정 */
	@PatchMapping("/line-items/{lineItemId}/purchase-status")
	public ResponseEntity<OrderLineItemResponse> updatePurchaseStatus(
		@PathVariable Long lineItemId,
		@RequestBody UpdatePurchaseStatusRequest request) {

		OrderLineItem updated = orderService.updatePurchaseStatus(lineItemId, request.getPurchaseStatus());
		return ResponseEntity.ok(OrderLineItemResponse.from(updated));
	}

	// ======================== 발송 ========================

	/** 일괄 발송 처리 */
	@PostMapping("/ship")
	public ResponseEntity<BulkShipResult> shipOrders(@RequestBody
	OrderShipRequest request) {

		// D-076/SP-3: 일괄 발송 처리 — 부분실패를 결과로 표면화하고 결과 기반으로 상태 기록(F-ORD-30).
		// 다마켓이므로 marketType은 null. 실패가 하나라도 있으면 FAILED로 남긴다(무조건 SUCCESS 금지).
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

	// 주문 삭제 엔드포인트(DELETE /{id})는 제거됨 — 물리삭제는 복구불가·연관데이터 고아를 유발하므로
	// 운영 정책상 지원하지 않는다(사용자 결정, 2026-07-14).
}
