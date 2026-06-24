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
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
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

		// 이미 접수 완료된 주문이면 스킵
		if (isOrderFullyPrepared(order)) {
			return order;
		}

		// 마켓 크레덴셜 조회 및 접수 API 호출
		MarketCredential credential = credentialRepository.findByMarketType(order.getMarketType())
			.orElseThrow(() -> new RuntimeException(order.getMarketType() + " credentials not found"));

		try {
			callMarketplaceAcceptApi(order, credential);
		} catch (Exception e) {
			log.error("마켓플레이스 주문 접수 API 실패: order={} ({}): {}",
				id, order.getMarketOrderNo(), e.getMessage());
			throw new RuntimeException("마켓플레이스 주문 접수 실패: " + e.getMessage(), e);
		}

		// NEW 상태 라인아이템을 PREPARING으로 변경
		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		for (OrderLineItem item : items) {
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

		int successCount = 0;
		List<Long> failedIds = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		for (Long id : ids) {
			try {
				confirmOrder(id);
				successCount++;
			} catch (Exception e) {
				failedIds.add(id);
				errors.add("Order " + id + ": " + e.getMessage());
				log.warn("주문 {} 접수 확인 실패: {}", id, e.getMessage());
			}
		}

		return BulkConfirmResult.builder()
			.successCount(successCount)
			.failedCount(failedIds.size())
			.failedIds(failedIds)
			.errors(errors.isEmpty() ? null : errors)
			.build();
	}

	// ======================== 발주취소 ========================

	/** 주문 취소 */
	@Transactional
	public Order cancelOrder(Long id) {

		// 주문 조회
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		// 라인아이템 배송상태를 CANCELED로 변경
		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		for (OrderLineItem item : items) {
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

		int successCount = 0;
		List<Long> failedIds = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		for (Long id : ids) {
			try {
				cancelOrder(id);
				successCount++;
			} catch (Exception e) {
				failedIds.add(id);
				errors.add("Order " + id + ": " + e.getMessage());
				log.warn("주문 {} 취소 실패: {}", id, e.getMessage());
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

		// NEW/UNKNOWN 상태이면 수정 차단
		ShippingStatus currentStatus = lineItem.getShippingData() != null
			? lineItem.getShippingData().getShippingStatus() : null;

		if (currentStatus == null || currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.UNKNOWN) {
			throw new IllegalStateException("발주확인 전에는 라인아이템 정보를 수정할 수 없습니다.");
		}

		// 유니패스완료여부 수정
		if (command.getIsUnipassDone() != null) {
			lineItem.updateUnipassDone(command.getIsUnipassDone());
		}

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

		// PREPARING이면 구매 처리 (PREPARING -> PURCHASED)
		if (currentStatus == ShippingStatus.PREPARING) {
			if (command.getSourcingOrderNo() == null || command.getSourcingOrderNo().isEmpty()) {
				throw new IllegalStateException("구매정보 수정 시 주문번호는 필수입니다.");
			}
			item.applySourcingData(command.toSourcingData(item.getSourcingData()));
			item.markAsPurchased();
			orderLineItemRepository.save(item);

			log.info("라인아이템 {} PURCHASED로 변경 (vendor: {}, orderNo: {})",
				lineItemId, command.getSourcingVendor(), command.getSourcingOrderNo());
		} else {
			// PURCHASED 이후면 단순 정보 수정
			item.applySourcingData(command.toSourcingData(item.getSourcingData()));
			orderLineItemRepository.save(item);

			log.info("라인아이템 {} 구매 정보 수정 완료", lineItemId);
		}

		return item;
	}

	/** 배송 정보 수정 */
	@Transactional
	public OrderLineItem updateShippingInfo(Long lineItemId, ShippingUpdateCommand command) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// NEW/UNKNOWN/PREPARING 상태이면 수정 차단
		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;

		if (currentStatus == null || currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.UNKNOWN
			|| currentStatus == ShippingStatus.PREPARING) {
			throw new IllegalStateException("발주확인 또는 구매완료 전에는 배송 정보를 수정할 수 없습니다.");
		}

		// PURCHASED면 배송 처리 (PURCHASED -> SHIPPED)
		if (currentStatus == ShippingStatus.PURCHASED) {
			item.applyShippingData(command.toShippingData(item.getShippingData()));
			item.markAsShipped();
			orderLineItemRepository.save(item);

			// 마켓플레이스에 송장 전송
			marketplaceShippingService.sendTrackingToMarketplace(item);
			item.markTrackingAsSent();
			orderLineItemRepository.save(item);

			log.info("라인아이템 {} 배송 처리: tracking={}, carrier={}", lineItemId, command.getTrackingNo(),
				command.getShippingCarrier());
		} else {
			// SHIPPED 이후면 송장 수정
			item.applyShippingData(command.toShippingData(item.getShippingData()));
			orderLineItemRepository.save(item);

			// 마켓플레이스에 송장 업데이트
			marketplaceShippingService.sendTrackingToMarketplace(item);
			item.markTrackingAsSent();
			orderLineItemRepository.save(item);

			log.info("라인아이템 {} 송장번호 업데이트: tracking={}, carrier={}", lineItemId,
				command.getTrackingNo(), command.getShippingCarrier());
		}

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

		// 소싱 데이터 적용 및 PURCHASED로 변경
		SourcingData data = SourcingData.builder()
			.sourcingVendor(sourcingVendor)
			.sourcingAccount(sourcingAccount)
			.sourcingOrderNo(sourcingOrderNo)
			.discountCode(discountCode)
			.build();
		item.applySourcingData(data);
		item.markAsPurchased();
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} PURCHASED로 변경 (vendor: {}, orderNo: {})",
			lineItemId, sourcingVendor, sourcingOrderNo);
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

		// 소싱 데이터 적용 및 PURCHASED로 변경
		SourcingData data = SourcingData.builder()
			.sourcingVendor(sourcingVendor)
			.sourcingAccount(sourcingAccount)
			.sourcingOrderNo(sourcingOrderNo)
			.sourcingAmount(emailAmount)
			.discountCode(discountCode)
			.build();
		item.applySourcingData(data);
		item.markAsPurchased();
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} PURCHASED로 변경 (vendor: {}, orderNo: {}, emailAmount: {})",
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

	/** 배송 처리 (PURCHASED -> SHIPPED) */
	@Transactional
	public void processShipping(Long lineItemId, String trackingNo,
		ShippingCarrier carrier) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// PURCHASED 상태 확인
		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PURCHASED) {
			throw new IllegalStateException("배송 처리는 PURCHASED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		// 배송 데이터 적용 및 SHIPPED로 변경
		ShippingData currentShipping = item.getShippingData();
		if (currentShipping == null) {
			currentShipping = ShippingData.builder().build();
		}
		item.applyShippingData(currentShipping.toBuilder()
			.trackingNo(trackingNo)
			.shippingCarrier(carrier)
			.shippingStatus(ShippingStatus.SHIPPED)
			.build());
		orderLineItemRepository.save(item);

		// 마켓플레이스에 송장 전송
		marketplaceShippingService.sendTrackingToMarketplace(item);
		item.markTrackingAsSent();
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} 배송 처리: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

	/** 송장 정보 업데이트 */
	@Transactional
	public void updateTrackingInfo(Long lineItemId, String trackingNo,
		ShippingCarrier carrier) {

		// 라인아이템 조회
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		// SHIPPED 상태 확인
		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.SHIPPED) {
			throw new IllegalStateException("송장 수정은 SHIPPED 상태에서만 가능합니다. 현재: " + currentStatus);
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

		// 마켓플레이스에 송장 업데이트
		marketplaceShippingService.sendTrackingToMarketplace(item);
		item.markTrackingAsSent();
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} 송장번호 업데이트: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

	// ======================== 삭제 ========================

	/** 주문 삭제 */
	@Transactional
	public void deleteOrder(Long id) {

		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(id);
		orderLineItemRepository.deleteAll(lineItems);
		orderRepository.deleteById(id);
	}

	// ======================== private ========================

	/** 접수 완료 여부 판단 */
	private boolean isOrderFullyPrepared(Order order) {
		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		return !items.isEmpty() && items.stream().allMatch(item -> {
			ShippingStatus status = item.getShippingData() != null
				? item.getShippingData().getShippingStatus() : null;
			return status == ShippingStatus.PREPARING || status == ShippingStatus.SHIPPED
				|| status == ShippingStatus.DELIVERED;
		});
	}

	/** 마켓플레이스 주문 접수 API 호출 */
	private void callMarketplaceAcceptApi(Order order, MarketCredential credential) {
		MarketOrderPort port = marketplaceShippingService.getPort(order.getMarketType());
		port.acceptOrders(credential, order);
	}

}
