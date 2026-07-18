package com.sbshop.agent.core.application.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbshop.agent.core.application.order.dto.BulkConfirmResult;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;
import com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand;
import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.application.order.dto.SourcingUpdateCommand;
import com.sbshop.agent.core.application.order.exception.MarketOrderAcceptException;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketCredentialRepository credentialRepository;
	private final MarketplaceShippingService marketplaceShippingService;

	// ======================== 조회 ========================

	/** 주문 검색 */
	public Page<OrderDetailDto> searchOrders(OrderSearchCondition condition,
		Pageable pageable) {

		return orderRepository.searchOrderGrid(condition, pageable);
	}

	// ======================== 발주확인 ========================

	/** 마켓플레이스 주문 접수 */
	@Transactional
	public Order confirmOrder(Long id) {

		// 주문 조회
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		// 마켓 타입 확인
		if (order.getMarketType() == null) {
			throw new IllegalStateException("Market type is not set for order: " + id);
		}

		// 이미 진행(PREPARING 이상)·종료(취소/반품/교환) 상태의 라인아이템이 있으면 재확인 차단 —
		// 발주확인은 결제완료(NEW) 상태에서만 최초 1회 수행한다(F-ORD-6, F-ORD-29).
		List<OrderLineItem> currentItems = orderLineItemRepository.findByOrderId(id);

		// 라인아이템이 하나도 없는 주문은 발주확인이 무의미하다 — 마켓 접수 API 호출 전에 차단(F-ORD-22).
		if (currentItems.isEmpty()) {
			throw new IllegalStateException("라인아이템이 없는 주문은 발주확인할 수 없습니다.");
		}

		boolean hasProgressedOrEnded = currentItems.stream().anyMatch(item -> {
			ShippingStatus status = item.getShippingData() != null ? item.getShippingData().getShippingStatus() : null;
			if (status == null) {
				return false;
			}
			boolean progressed = status.getOrder() >= ShippingStatus.PREPARING.getOrder();
			boolean ended = status == ShippingStatus.CANCELED || status == ShippingStatus.RETURNED
				|| status == ShippingStatus.EXCHANGED;
			return progressed || ended;
		});
		if (hasProgressedOrEnded) {
			throw new IllegalStateException("이미 발주확인되었거나 종료된 주문은 재확인할 수 없습니다.");
		}

		// 마켓 크레덴셜 조회 및 접수 API 호출
		MarketCredential credential = credentialRepository.findByMarketType(order.getMarketType())
			.orElseThrow(() -> new RuntimeException(order.getMarketType() + " credentials not found"));

		try {
			callMarketplaceAcceptApi(order, credential);
		} catch (Exception e) {
			log.error("마켓플레이스 주문 접수 API 실패: order={} ({}): {}",
				id, order.getMarketOrderNo(), e.getMessage());
			// 접수 실패 유형·원인을 전용 예외 + cause 체이닝으로 보존한다(F-ORD-8). 응답 계약(500)은 종전 유지.
			throw new MarketOrderAcceptException("마켓플레이스 주문 접수 실패: " + e.getMessage(), e);
		}

		// NEW 상태 라인아이템을 PREPARING으로 변경
		for (OrderLineItem item : currentItems) {
			ShippingStatus currentStatus = item.getShippingData() != null
				? item.getShippingData().getShippingStatus() : null;
			if (currentStatus == ShippingStatus.NEW) {
				ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
					.trackingNo(item.getShippingData() != null ? item.getShippingData().getTrackingNo() : null)
					.shippingStatus(ShippingStatus.PREPARING)
					.build();
				item.applyShippingData(cmd.toShippingData(item.getShippingData()));
				orderLineItemRepository.save(item);
			}
		}

		log.info("주문 {} 접수 확인 및 상태를 PREPARING로 변경", id);
		return order;
	}

	/** 주문 일괄 접수 */
	@Transactional
	public BulkConfirmResult bulkConfirmOrders(List<Long> ids) {
		return bulkOperate(ids, this::confirmOrder, "접수 확인");
	}

	// ======================== 발주취소 ========================

	/** 주문 취소 */
	@Transactional
	public Order cancelOrder(Long id) {

		// 주문 조회
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		// 발주취소는 NEW(결제완료·미접수) 상태에서만 허용 — 이미 접수/구매/배송/종료된 건은
		// 취소가 아니라 각 마켓의 반품·교환 절차 대상이므로 여기서 차단한다(F-ORD-13).
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
		boolean allNew = lineItems.stream().allMatch(item -> {
			ShippingStatus status = item.getShippingData() != null ? item.getShippingData().getShippingStatus() : null;
			return status == ShippingStatus.NEW;
		});
		if (!allNew) {
			throw new IllegalStateException("발주취소는 결제완료(NEW) 상태에서만 가능합니다.");
		}

		// G마켓/옥션은 Cafe24 주문상태 API로 실제 취소를 전파(그 외 마켓은 현행 로컬-only 유지).
		MarketType mt = order.getMarketType();
		if (mt == MarketType.GMARKET || mt == MarketType.AUCTION) {
			try {
				marketplaceShippingService.cancelOrderToMarketplace(order);
			} catch (Exception e) {
				log.error("마켓 주문취소 전파 실패: order={} ({}): {}", id, order.getMarketOrderNo(), e.getMessage());
				throw new RuntimeException("마켓 주문취소 실패: " + e.getMessage(), e);
			}
		}

		// 라인아이템 배송상태를 CANCELED로 변경
		for (OrderLineItem item : lineItems) {
			if (item.getShippingData() != null) {
				ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
					.trackingNo(item.getShippingData().getTrackingNo())
					.shippingStatus(ShippingStatus.CANCELED)
					.build();
				item.applyShippingData(cmd.toShippingData(item.getShippingData()));
				orderLineItemRepository.save(item);
			}
		}

		return order;
	}

	/** 주문 일괄 취소 */
	@Transactional
	public BulkConfirmResult bulkCancelOrders(List<Long> ids) {
		return bulkOperate(ids, this::cancelOrder, "취소");
	}

	/**
	 * 주문 ID 목록을 순회하며 건별 작업(op)을 수행하고, 성공 카운트·실패 ID·에러 메시지를 집계한다.
	 * 건별 실패는 배치를 중단시키지 않고 수집만 한다(부분 성공 허용).
	 * bulkConfirmOrders/bulkCancelOrders의 동일 루프를 통합한 공통 헬퍼(F-ORD-18).
	 *
	 * @param op 건별 처리(confirmOrder/cancelOrder). 예외를 던지면 실패로 집계된다.
	 * @param actionLabel 실패 로그에 쓰는 작업명(예: "접수 확인", "취소").
	 */
	private BulkConfirmResult bulkOperate(List<Long> ids, java.util.function.LongConsumer op,
		String actionLabel) {

		int successCount = 0;
		List<Long> failedIds = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		for (Long id : ids) {
			try {
				op.accept(id);
				successCount++;
			} catch (Exception e) {
				failedIds.add(id);
				errors.add("Order " + id + ": " + e.getMessage());
				log.warn("주문 {} {} 실패: {}", id, actionLabel, e.getMessage());
			}
		}

		return BulkConfirmResult.builder()
			.successCount(successCount)
			.failedCount(failedIds.size())
			.failedIds(failedIds)
			.errors(errors.isEmpty() ? null : errors)
			.build();
	}

	// ======================== 수정 ========================

	/** 주소/통관번호 사용자 수정 */
	@Transactional
	public Order updateOrder(Long id, OrderUpdateCommand command) {

		// 주문 조회
		Order order = orderRepository
			.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		// NEW/UNKNOWN 상태이면 수정 차단
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(id);
		boolean isAllNew = lineItems.stream().allMatch(item -> {
			ShippingStatus status = item.getShippingData() != null ? item.getShippingData().getShippingStatus() : null;
			return status == null || status == ShippingStatus.NEW || status == ShippingStatus.UNKNOWN;
		});

		if (isAllNew && !lineItems.isEmpty()) {
			throw new IllegalStateException("발주확인 전에는 주문 정보를 수정할 수 없습니다.");
		}

		// 주소 수정
		if (command.getAddress() != null) {
			order.updateAddress(command.getAddress());
		}

		// 통관번호 수정
		if (command.getCustomsClearanceNo() != null) {
			order.updateCustomsClearanceNo(command.getCustomsClearanceNo());
		}

		return order;
	}

	/** 유니패스완료여부 사용자 수정 */
	@Transactional
	public OrderLineItem updateOrderLineItem(Long id, OrderLineItemUpdateCommand command) {

		// 라인아이템 조회
		OrderLineItem lineItem = orderLineItemRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + id));

		// 유니패스 신고여부는 관리용 정보로, 배송상태와 무관하게 언제든 수정 가능(F-ORD-25 종결).

		// isUnipassDone이 없는 요청은 변경할 것이 없다 — no-op을 성공으로 오인해 로그를 남기지 않도록 차단(F-ORD-26).
		if (command.getIsUnipassDone() == null) {
			throw new IllegalArgumentException("유니패스 완료여부(isUnipassDone)는 필수입니다.");
		}

		// 유니패스완료여부 수정
		lineItem.updateUnipassDone(command.getIsUnipassDone());

		return orderLineItemRepository.save(lineItem);
	}

	/** 소싱 정보 수정 */
	@Transactional
	public OrderLineItem updateSourcingInfo(Long lineItemId, SourcingUpdateCommand command) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// NEW/UNKNOWN 상태이면 수정 차단
		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;

		if (currentStatus == null || currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.UNKNOWN) {
			throw new IllegalStateException("발주확인 전에는 구매 정보를 수정할 수 없습니다.");
		}

		// 주문번호 필수 가드 제거: 구매상태(PurchaseStatus)가 독립 필드로 분리된 뒤로 주문번호 강제는 불필요.
		// 항상 편집 가능한 그리드에서 주문번호 삭제·부분수정을 허용한다.

		// 소싱데이터 반영 후 저장. 구매상태(PurchaseStatus)는 이 경로에서 자동 변경하지 않는다 —
		// 유저가 그리드 구매상태 셀렉트로 수동 관리한다. 이메일 페치는 주문번호(IHB) 유무로 대상을 판단한다.
		item.applySourcingData(command.toSourcingData(item.getSourcingData()));
		orderLineItemRepository.save(item);
		log.info("라인아이템 {} 구매 정보 수정 완료", lineItemId);

		return item;
	}

	/** 배송 정보 수정 */
	@Transactional
	public OrderLineItem updateShippingInfo(Long lineItemId, ShippingUpdateCommand command) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// 이번 편집 이전에 마켓에 송장이 이미 존재했는지(=동기화로 유입된 송장 포함) — 초기등록/수정 판단 근거.
		// 반드시 applyShippingData로 새 송장을 덮어쓰기 전에 계산한다.
		boolean invoiceAlreadyExists = item.getShippingData() != null
			&& item.getShippingData().getTrackingNo() != null
			&& !item.getShippingData().getTrackingNo().isBlank();

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;

		// 종료(취소/반품/교환) 상태는 송장 수정 대상이 아니다 — 마켓이 진실 원본이므로 로컬 저장도 하지 않고 차단(F-H2).
		if (currentStatus == ShippingStatus.CANCELED || currentStatus == ShippingStatus.RETURNED
			|| currentStatus == ShippingStatus.EXCHANGED) {
			throw new IllegalStateException("종료된 주문(취소/반품/교환)은 마켓 송장 전송 대상이 아닙니다.");
		}

		// NEW/UNKNOWN 상태이면 수정 차단 (PREPARING은 이제 허용 — 첫 송장 등록 진입점)
		if (currentStatus == null || currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.UNKNOWN) {
			throw new IllegalStateException("발주확인 전에는 배송 정보를 수정할 수 없습니다.");
		}

		// PREPARING이면 최초 배송처리 → DISPATCHED, DISPATCHED/SHIPPED 이후는 송장만 수정.
		boolean isDispatchTransition = currentStatus == ShippingStatus.PREPARING;

		// PREPARING→DISPATCHED 최초 전이에는 송장번호 필수 — 없으면 송장 없이 DISPATCHED로 넘어가는 것을 차단(F-H4).
		// (소싱의 sourcingOrderNo 가드와 대칭. DISPATCHED 이후 단순수정은 이 가드 없이 허용.)
		if (isDispatchTransition && (command.getTrackingNo() == null || command.getTrackingNo().isBlank())) {
			throw new IllegalStateException("배송 처리 시 송장번호는 필수입니다.");
		}

		// 공통: 송장데이터 반영 → (PREPARING → DISPATCHED 전이 시에만 마킹) → 저장
		item.applyShippingData(command.toShippingData(item.getShippingData()));
		if (isDispatchTransition) {
			item.markAsDispatched();
		}
		orderLineItemRepository.save(item);

		// 마켓플레이스에 송장 전송/업데이트 — 반영 실패 시 자사 저장을 롤백해 DB/마켓 정합을 유지(@Transactional),
		// 스킵(전송 대상 아님)은 로컬 편집 유지, 성공 시에만 전송완료 마킹.
		MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, invoiceAlreadyExists);
		failIfNotSent(item, sendResult);
		markSentIfSucceeded(item, sendResult, lineItemId);

		if (isDispatchTransition) {
			log.info("라인아이템 {} 배송지시 처리: tracking={}, carrier={}", lineItemId, command.getTrackingNo(),
				command.getShippingCarrier());
		} else {
			log.info("라인아이템 {} 송장번호 업데이트: tracking={}, carrier={}", lineItemId,
				command.getTrackingNo(), command.getShippingCarrier());
		}

		return item;
	}

	/** 라인아이템 구매 상태 변경 */
	@Transactional
	public OrderLineItem updatePurchaseStatus(Long lineItemId, PurchaseStatus purchaseStatus) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));
		item.updatePurchaseStatus(purchaseStatus);
		orderLineItemRepository.save(item);
		log.info("라인아이템 {} 구매상태 변경: {}", lineItemId, purchaseStatus);
		return item;
	}

	/** 라인아이템 구매 처리 */
	@Transactional
	public void markAsPurchased(Long lineItemId, String sourcingAccount,
		String sourcingOrderNo, String discountCode, String sourcingVendor) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// PREPARING 상태 확인
		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PREPARING) {
			throw new IllegalStateException(
				"구매 처리는 PREPARING 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		// 소싱 데이터 적용 및 purchaseStatus를 PURCHASED로 변경
		SourcingData data = SourcingData.builder()
			.sourcingVendor(sourcingVendor)
			.sourcingAccount(sourcingAccount)
			.sourcingOrderNo(sourcingOrderNo)
			.discountCode(discountCode)
			.build();
		item.applySourcingData(data);
		item.updatePurchaseStatus(PurchaseStatus.PURCHASED);
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} 구매완료로 변경 (account: {}, orderNo: {})",
			lineItemId, sourcingAccount, sourcingOrderNo);
	}

	/** 라인아이템 구매 처리 (금액 포함) */
	@Transactional
	public void markAsPurchasedWithAmount(Long lineItemId, String sourcingAccount,
		String sourcingOrderNo, String discountCode, String sourcingVendor,
		BigDecimal emailAmount) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// PREPARING 상태 확인
		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PREPARING) {
			throw new IllegalStateException(
				"구매 처리는 PREPARING 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		// 소싱 데이터 적용 및 purchaseStatus를 PURCHASED로 변경
		SourcingData data = SourcingData.builder()
			.sourcingVendor(sourcingVendor)
			.sourcingAccount(sourcingAccount)
			.sourcingOrderNo(sourcingOrderNo)
			.sourcingAmount(emailAmount)
			.discountCode(discountCode)
			.build();
		item.applySourcingData(data);
		item.updatePurchaseStatus(PurchaseStatus.PURCHASED);
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} 구매완료로 변경 (vendor: {}, orderNo: {}, emailAmount: {})",
			lineItemId, sourcingVendor, sourcingOrderNo, emailAmount);
	}

	/** 실구매가 업데이트 */
	@Transactional
	public void updateSourcingAmount(Long lineItemId, BigDecimal amount) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// 소싱 데이터에서 실구매가 업데이트
		SourcingData current = item.getSourcingData();
		SourcingData updated = (current != null ? current
			: SourcingData.builder().build())
			.toBuilder()
			.sourcingAmount(amount)
			.build();
		item.applySourcingData(updated);
		orderLineItemRepository.save(item);

		log.info("LineItem {} 실구매가 업데이트: {}", lineItemId, amount);
	}

	/** 배송 처리 (PREPARING → DISPATCHED) */
	@Transactional
	public void processShipping(Long lineItemId, String trackingNo,
		ShippingCarrier carrier) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// PREPARING 또는 DISPATCHED 상태 확인
		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PREPARING && currentStatus != ShippingStatus.DISPATCHED) {
			throw new IllegalStateException("배송 처리는 PREPARING 또는 DISPATCHED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		// 이번 편집 이전에 마켓에 송장이 이미 존재했는지 — 초기등록/수정 판단 근거(applyShippingData 전에 계산).
		boolean invoiceAlreadyExists = item.getShippingData() != null
			&& item.getShippingData().getTrackingNo() != null
			&& !item.getShippingData().getTrackingNo().isBlank();

		// 배송 데이터 적용 및 DISPATCHED로 변경
		ShippingData currentShipping = item.getShippingData() != null
			? item.getShippingData() : ShippingData.builder().build();
		item.applyShippingData(currentShipping.toBuilder()
			.trackingNo(trackingNo)
			.shippingCarrier(carrier)
			.shippingStatus(ShippingStatus.DISPATCHED)
			.build());
		orderLineItemRepository.save(item);

		// 마켓플레이스에 송장 전송 — 실패해도 위의 배송정보 저장은 보존, 성공 시에만 전송완료 마킹
		MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, invoiceAlreadyExists);
		markSentIfSucceeded(item, sendResult, lineItemId);

		log.info("라인아이템 {} 배송지시 처리: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

	/** 송장 정보 업데이트 */
	@Transactional
	public void updateTrackingInfo(Long lineItemId, String trackingNo,
		ShippingCarrier carrier) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// DISPATCHED 또는 SHIPPED 상태 확인
		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.DISPATCHED && currentStatus != ShippingStatus.SHIPPED) {
			throw new IllegalStateException("송장 수정은 DISPATCHED 또는 SHIPPED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		// 송장 정보 업데이트
		ShippingData currentShipping = item.getShippingData();
		if (currentShipping == null) {
			currentShipping = ShippingData.builder().build();
		}
		item.applyShippingData(currentShipping.toBuilder()
			.trackingNo(trackingNo)
			.shippingCarrier(carrier)
			.build());
		orderLineItemRepository.save(item);

		// 마켓플레이스에 송장 업데이트 — 실패해도 위의 배송정보 저장은 보존, 성공 시에만 전송완료 마킹.
		// 이 메서드는 SHIPPED 상태에서만 진입하므로(위 가드) 마켓에 송장이 이미 존재 → 항상 수정(updateTracking).
		MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, true);
		markSentIfSucceeded(item, sendResult, lineItemId);

		log.info("라인아이템 {} 송장번호 업데이트: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

	// ======================== 로그용 조회 (부작용 없음) ========================

	/**
	 * 라인아이템이 속한 주문의 마켓 타입을 반환한다(활동로그용 read-only 조회).
	 * 라인아이템·주문·마켓이 하나라도 없으면 null. (F-ORD-27/F-S6/F-H6)
	 */
	public MarketType marketTypeOfLineItem(Long lineItemId) {
		return orderLineItemRepository.findById(lineItemId)
			.map(OrderLineItem::getOrderId)
			.flatMap(orderRepository::findById)
			.map(Order::getMarketType)
			.orElse(null);
	}

	/**
	 * 주문의 마켓 타입을 반환한다(활동로그용 read-only 조회).
	 * 주문·마켓이 하나라도 없으면 null(=조회 실패 시 null 유지). (F-ORD-5/F-ORD-15)
	 */
	public MarketType marketTypeOfOrder(Long orderId) {
		return orderRepository.findById(orderId)
			.map(Order::getMarketType)
			.orElse(null);
	}

	// ======================== private ========================

	/** 라인아이템이 속한 주문의 마켓 타입(에러 메시지용, 조회 실패 시 UNKNOWN 표기). */
	private String marketTypeOf(OrderLineItem item) {
		return orderRepository.findById(item.getOrderId())
			.map(Order::getMarketType)
			.map(Object::toString)
			.orElse("UNKNOWN");
	}

	/**
	 * 마켓 전송이 실패(전송 대상이었으나 반영 실패)면 예외를 던져 @Transactional 롤백으로 DB/마켓 정합을 유지한다.
	 * terminal(마켓이 배송중/완료로 영구 잠금)과 일시 failed를 구분된 메시지로 표면화한다(F-H1).
	 */
	private void failIfNotSent(OrderLineItem item, MarketShippingResult result) {
		if (!result.isFailed()) {
			return;
		}
		if (result.isTerminal()) {
			throw new IllegalStateException("마켓(" + marketTypeOf(item)
				+ ")이 배송중/완료 상태라 송장 수정 불가 — 마켓 송장이 동기화로 반영됩니다: " + result.failureReason());
		}
		throw new IllegalStateException("마켓(" + marketTypeOf(item)
			+ ") 송장 반영 실패로 저장을 롤백합니다: " + result.failureReason());
	}

	/**
	 * 마켓 전송이 실제로 성공한 경우에만 trackingSentToMarket을 마킹하고 저장한다.
	 * 스킵(전송 대상 아님)이면 마킹하지 않는다 — 로컬 편집은 그대로 보존된다.
	 * 실패(isFailed)는 호출부에서 예외를 던져 @Transactional 롤백으로 처리하므로 이 지점에 도달하지 않는다.
	 * (종전 D-069는 실패 시에도 저장을 보존했으나, DB/마켓 정합을 위해 실패는 롤백하도록 계약을 변경함.)
	 */
	private void markSentIfSucceeded(OrderLineItem item, MarketShippingResult result, Long lineItemId) {
		if (result.sent()) {
			item.markTrackingAsSent();
			orderLineItemRepository.save(item);
		} else if (result.isFailed()) {
			// 도달 불가(호출부가 isFailed에서 이미 throw). 방어적 로깅만 유지.
			log.warn("라인아이템 {} 마켓 송장 전송 실패 — 롤백 예정: {}", lineItemId, result.failureReason());
		}
	}

	/** 마켓플레이스 주문 접수 API 호출 */
	private void callMarketplaceAcceptApi(Order order, MarketCredential credential) {
		MarketOrderPort port = marketplaceShippingService.getPort(order.getMarketType());
		port.acceptOrders(credential, order);
	}

}
