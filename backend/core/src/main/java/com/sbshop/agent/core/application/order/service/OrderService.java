package com.sbshop.agent.core.application.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import java.util.function.LongConsumer;
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
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
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
	private final LineItemShippingWriter shippingWriter;

	public Page<OrderDetailDto> searchOrders(OrderSearchCondition condition,
		Pageable pageable) {
		return orderRepository.searchOrderGrid(condition, pageable);
	}

	@Transactional
	public Order confirmOrder(Long id) {
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		if (order.getMarketType() == null) {
			throw new IllegalStateException("Market type is not set for order: " + id);
		}

		List<OrderLineItem> currentItems = orderLineItemRepository.findByOrderId(id);

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

		MarketCredential credential = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);

		try {
			callMarketplaceAcceptApi(order, credential);
		} catch (Exception e) {
			log.error("마켓플레이스 주문 접수 API 실패: order={} ({}): {}",
				id, order.getMarketOrderNo(), e.getMessage());
			throw new MarketOrderAcceptException("마켓플레이스 주문 접수 실패: " + e.getMessage(), e);
		}

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

	@Transactional
	public BulkConfirmResult bulkConfirmOrders(List<Long> ids) {
		return bulkOperate(ids, this::confirmOrder, "접수 확인");
	}

	@Transactional
	public Order cancelOrder(Long id) {
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
		boolean allNew = lineItems.stream().allMatch(item -> {
			ShippingStatus status = item.getShippingData() != null ? item.getShippingData().getShippingStatus() : null;
			return status == ShippingStatus.NEW;
		});
		if (!allNew) {
			throw new IllegalStateException("발주취소는 결제완료(NEW) 상태에서만 가능합니다.");
		}

		MarketType mt = order.getMarketType();
		if (mt == MarketType.GMARKET || mt == MarketType.AUCTION) {
			try {
				marketplaceShippingService.cancelOrderToMarketplace(order);
			} catch (Exception e) {
				log.error("마켓 주문취소 전파 실패: order={} ({}): {}", id, order.getMarketOrderNo(), e.getMessage());
				throw new RuntimeException("마켓 주문취소 실패: " + e.getMessage(), e);
			}
		}

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

	@Transactional
	public BulkConfirmResult bulkCancelOrders(List<Long> ids) {
		return bulkOperate(ids, this::cancelOrder, "취소");
	}

	@Transactional
	public Order updateOrder(Long id, OrderUpdateCommand command) {
		Order order = orderRepository
			.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(id);
		boolean isAllNew = lineItems.stream().allMatch(item -> {
			ShippingStatus status = item.getShippingData() != null ? item.getShippingData().getShippingStatus() : null;
			return status == null || status == ShippingStatus.NEW || status == ShippingStatus.UNKNOWN;
		});

		if (isAllNew && !lineItems.isEmpty()) {
			throw new IllegalStateException("발주확인 전에는 주문 정보를 수정할 수 없습니다.");
		}

		if (command.getAddress() != null) {
			order.updateAddress(command.getAddress());
		}

		if (command.getCustomsClearanceNo() != null) {
			order.updateCustomsClearanceNo(command.getCustomsClearanceNo());
		}

		if (command.getMessage() != null) {
			order.updateMessage(command.getMessage());
		}

		return order;
	}

	@Transactional
	public OrderLineItem updateOrderLineItem(Long id, OrderLineItemUpdateCommand command) {
		OrderLineItem lineItem = orderLineItemRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + id));

		if (command.getIsUnipassDone() == null) {
			throw new IllegalArgumentException("유니패스 완료여부(isUnipassDone)는 필수입니다.");
		}

		lineItem.updateUnipassDone(command.getIsUnipassDone());

		return orderLineItemRepository.save(lineItem);
	}

	@Transactional
	public OrderLineItem updateSourcingInfo(Long lineItemId, SourcingUpdateCommand command) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;

		if (currentStatus == null || currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.UNKNOWN) {
			throw new IllegalStateException("발주확인 전에는 구매 정보를 수정할 수 없습니다.");
		}

		item.applySourcingData(command.toSourcingData(item.getSourcingData()));
		orderLineItemRepository.save(item);
		log.info("라인아이템 {} 구매 정보 수정 완료", lineItemId);

		return item;
	}

	@Transactional
	public OrderLineItem updateShippingInfo(Long lineItemId, ShippingUpdateCommand command) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		boolean invoiceAlreadyExists = item.getShippingData() != null
			&& item.getShippingData().getTrackingNo() != null
			&& !item.getShippingData().getTrackingNo().isBlank();

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;

		if (currentStatus == ShippingStatus.CANCELED || currentStatus == ShippingStatus.RETURNED
			|| currentStatus == ShippingStatus.EXCHANGED) {
			throw new IllegalStateException("종료된 주문(취소/반품/교환)은 마켓 송장 전송 대상이 아닙니다.");
		}

		if (currentStatus == null || currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.UNKNOWN) {
			throw new IllegalStateException("발주확인 전에는 배송 정보를 수정할 수 없습니다.");
		}

		boolean isDispatchTransition = currentStatus == ShippingStatus.PREPARING;

		if (isDispatchTransition && (command.getTrackingNo() == null || command.getTrackingNo().isBlank())) {
			throw new IllegalStateException("배송 처리 시 송장번호는 필수입니다.");
		}

		ShippingData next = command.toShippingData(item.getShippingData());
		if (isDispatchTransition) {
			next = next.toBuilder().shippingStatus(ShippingStatus.DISPATCHED).build();
		}
		shippingWriter.applyShipping(item, next, TrackingSource.MANUAL);

		MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item,
			invoiceAlreadyExists);
		logIfNotSent(item, sendResult);
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

	@Transactional
	public OrderLineItem updatePurchaseStatus(Long lineItemId, PurchaseStatus purchaseStatus) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));
		item.updatePurchaseStatus(purchaseStatus);
		orderLineItemRepository.save(item);
		log.info("라인아이템 {} 구매상태 변경: {}", lineItemId, purchaseStatus);
		return item;
	}

	@Transactional
	public void markAsPurchased(Long lineItemId, String sourcingAccount,
		String sourcingOrderNo, String discountCode, String sourcingVendor) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PREPARING) {
			throw new IllegalStateException(
				"구매 처리는 PREPARING 상태에서만 가능합니다. 현재: " + currentStatus);
		}

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

	@Transactional
	public void markAsPurchasedWithAmount(Long lineItemId, String sourcingAccount,
		String sourcingOrderNo, String discountCode, String sourcingVendor,
		BigDecimal emailAmount) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PREPARING) {
			throw new IllegalStateException(
				"구매 처리는 PREPARING 상태에서만 가능합니다. 현재: " + currentStatus);
		}

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

	@Transactional
	public void updateSourcingAmount(Long lineItemId, BigDecimal amount) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

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

	@Transactional
	public void processShipping(Long lineItemId, String trackingNo,
		ShippingCarrier carrier) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PREPARING && currentStatus != ShippingStatus.DISPATCHED) {
			throw new IllegalStateException("배송 처리는 PREPARING 또는 DISPATCHED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		boolean invoiceAlreadyExists = item.getShippingData() != null
			&& item.getShippingData().getTrackingNo() != null
			&& !item.getShippingData().getTrackingNo().isBlank();

		ShippingData currentShipping = item.getShippingData() != null
			? item.getShippingData() : ShippingData.builder().build();
		shippingWriter.applyShipping(item, currentShipping.toBuilder()
			.trackingNo(trackingNo)
			.shippingCarrier(carrier)
			.shippingStatus(ShippingStatus.DISPATCHED)
			.build(), TrackingSource.MANUAL);

		MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item,
			invoiceAlreadyExists);
		markSentIfSucceeded(item, sendResult, lineItemId);

		log.info("라인아이템 {} 배송지시 처리: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

	@Transactional
	public void updateTrackingInfo(Long lineItemId, String trackingNo,
		ShippingCarrier carrier) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.DISPATCHED && currentStatus != ShippingStatus.SHIPPED) {
			throw new IllegalStateException("송장 수정은 DISPATCHED 또는 SHIPPED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		ShippingData currentShipping = item.getShippingData();
		if (currentShipping == null) {
			currentShipping = ShippingData.builder().build();
		}
		shippingWriter.applyShipping(item, currentShipping.toBuilder()
			.trackingNo(trackingNo)
			.shippingCarrier(carrier)
			.build(), TrackingSource.MANUAL);

		MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, true);
		markSentIfSucceeded(item, sendResult, lineItemId);

		log.info("라인아이템 {} 송장번호 업데이트: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

	public MarketType marketTypeOfLineItem(Long lineItemId) {
		return orderLineItemRepository.findById(lineItemId)
			.map(OrderLineItem::getOrderId)
			.flatMap(orderRepository::findById)
			.map(Order::getMarketType)
			.orElse(null);
	}

	public MarketType marketTypeOfOrder(Long orderId) {
		return orderRepository.findById(orderId)
			.map(Order::getMarketType)
			.orElse(null);
	}

	private void callMarketplaceAcceptApi(Order order, MarketCredential credential) {
		MarketOrderPort port = marketplaceShippingService.getPort(order.getMarketType());
		port.acceptOrders(credential, order);
	}

	private BulkConfirmResult bulkOperate(List<Long> ids, LongConsumer op,
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

	private void logIfNotSent(OrderLineItem item, MarketShippingResult result) {
		if (!result.isFailed()) {
			return;
		}
		if (result.isTerminal()) {
			log.warn("라인아이템 {} 마켓({}) 영구 거부 — 로컬 송장은 보존, 마켓 반영 불가: {}",
				item.getId(), marketTypeOf(item), result.failureReason());
			return;
		}
		log.warn("라인아이템 {} 마켓({}) 송장 반영 실패 — 로컬 송장은 보존, 다음 재시도 대상: {}",
			item.getId(), marketTypeOf(item), result.failureReason());
	}

	private String marketTypeOf(OrderLineItem item) {
		return orderRepository.findById(item.getOrderId())
			.map(Order::getMarketType)
			.map(Object::toString)
			.orElse("UNKNOWN");
	}

	private void markSentIfSucceeded(OrderLineItem item, MarketShippingResult result, Long lineItemId) {
		if (result.sent()) {
			shippingWriter.markTrackingAsSent(item);
		} else if (result.isFailed()) {
			log.warn("라인아이템 {} 마켓 송장 전송 실패 — 롤백 예정: {}", lineItemId, result.failureReason());
		}
	}
}
