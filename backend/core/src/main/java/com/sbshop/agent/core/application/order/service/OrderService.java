package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketCredentialRepository credentialRepository;
	private final List<MarketOrderPort> marketOrderPorts;

	/**
	 * MarketType에 해당하는 MarketOrderPort를 찾는 헬퍼 메서드
	 */
	private MarketOrderPort getPort(MarketType marketType) {
		return marketOrderPorts.stream()
			.filter(port -> port.getMarketType() == marketType)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"지원하지 않는 마켓: " + marketType));
	}

	public Page<OrderDetailDto> searchOrders(OrderSearchCondition condition,
		Pageable pageable) {
		return orderRepository.searchOrderGrid(condition, pageable);
	}

	@Transactional
	public Order updateOrder(Long id, OrderUpdateCommand command) {
		Order order = orderRepository
			.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		order.updateInfo(
			command.getRecipientName(),
			command.getRecipientPhone(),
			command.getZipcode(),
			command.getAddress(),
			command.getMessage());

		order.updateCustomsStatus(command.getCustomsStatus());
		order.updateCustomsClearanceNo(command.getCustomsClearanceNo());

		return order;
	}

	@Transactional
	public OrderLineItem updateOrderLineItem(Long id, OrderLineItemUpdateCommand command) {
		OrderLineItem lineItem = orderLineItemRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("OrderLineItem not found: " + id));

		String oldTrackingNo = lineItem.getShippingData() != null ? lineItem.getShippingData().getTrackingNo() : null;
		boolean trackingChanged = command.getTrackingNo() != null
			&& !command.getTrackingNo().equals(oldTrackingNo)
			&& !command.getTrackingNo().isEmpty();

		lineItem.updateShipping(command.getTrackingNo(), command.getShippingStatus(), command.getIsUnipassDone());

		com.sbshop.agent.core.domain.order.vo.ShippingData.ShippingDataBuilder sdBuilder = lineItem
			.getShippingData() != null
				? lineItem.getShippingData().toBuilder()
				: com.sbshop.agent.core.domain.order.vo.ShippingData.builder();
		if (command.getShippingCarrier() != null)
			sdBuilder.shippingCarrier(command.getShippingCarrier());
		if (command.getTrackingSentToMarket() != null)
			sdBuilder.trackingSentToMarket(command.getTrackingSentToMarket());

		com.sbshop.agent.core.domain.order.vo.SourcingData.SourcingDataBuilder sourcingBuilder = lineItem
			.getSourcingData() != null ? lineItem.getSourcingData().toBuilder()
				: com.sbshop.agent.core.domain.order.vo.SourcingData.builder();
		if (command.getSourcingAccount() != null)
			sourcingBuilder.sourcingAccount(command.getSourcingAccount());
		if (command.getSourcingOrderNo() != null)
			sourcingBuilder.sourcingOrderNo(command.getSourcingOrderNo());
		if (command.getSourcingAmount() != null)
			sourcingBuilder.sourcingAmount(command.getSourcingAmount());
		if (command.getDiscountCode() != null)
			sourcingBuilder.discountCode(command.getDiscountCode());
		if (command.getSourcingVendor() != null)
			sourcingBuilder.sourcingVendor(command.getSourcingVendor());

		com.sbshop.agent.core.domain.order.vo.SettlementData.SettlementDataBuilder settlementBuilder = lineItem
			.getSettlementData() != null ? lineItem.getSettlementData().toBuilder()
				: com.sbshop.agent.core.domain.order.vo.SettlementData.builder();
		if (command.getShippingFee() != null)
			settlementBuilder.shippingFee(command.getShippingFee());
		if (command.getSettlementAmount() != null)
			settlementBuilder.settlementAmount(command.getSettlementAmount());

		lineItem.updateSourcingData(sourcingBuilder.build());

		com.sbshop.agent.core.domain.order.vo.SettlementData newSettlement = settlementBuilder.build();

		lineItem.updateSettlementData(newSettlement);
		lineItem.updateShippingData(sdBuilder.build());

		OrderLineItem saved = orderLineItemRepository.save(lineItem);

		if (trackingChanged) {
			syncTrackingToMarketplace(saved);
		}

		return saved;
	}

	private void syncTrackingToMarketplace(OrderLineItem lineItem) {
		Order order = orderRepository.findById(lineItem.getOrderId()).orElse(null);
		if (order == null)
			return;

		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
		if (cred == null)
			return;

		try {
			MarketOrderPort port = getPort(order.getMarketType());
			port.shipOrder(cred, order, lineItem,
				lineItem.getShippingData().getTrackingNo(),
				lineItem.getShippingData().getShippingCarrier());
			log.info("마켓 배송 동기화 완료: order={}, market={}", order.getMarketOrderNo(), order.getMarketType());
		} catch (Exception e) {
			log.error("마켓 배송 동기화 실패: order={}, error={}", order.getMarketOrderNo(), e.getMessage());
		}
	}

	@Transactional
	public void deleteOrder(Long id) {
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(id);
		orderLineItemRepository.deleteAll(lineItems);
		orderRepository.deleteById(id);
	}

	@Transactional
	public void confirmOrder(Long id) {
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		if (order.getMarketType() == null) {
			throw new IllegalStateException("Market type is not set for order: " + id);
		}

		if (isOrderFullyPrepared(order)) {
			return;
		}

		MarketCredential credential = credentialRepository.findByMarketType(order.getMarketType())
			.orElseThrow(() -> new RuntimeException(order.getMarketType() + " credentials not found"));

		try {
			callMarketplaceAcceptApi(order, credential);
		} catch (Exception e) {
			log.error("Marketplace accept API failed for order {} ({}): {}",
				id, order.getMarketOrderNo(), e.getMessage());
			throw new RuntimeException("마켓플레이스 주문 접수 실패: " + e.getMessage(), e);
		}

		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		for (OrderLineItem item : items) {
			ShippingStatus currentStatus = item.getShippingData() != null
				? item.getShippingData().getShippingStatus() : null;
			if (currentStatus == ShippingStatus.NEW) {
				item.updateShipping(item.getShippingData().getTrackingNo(),
					ShippingStatus.PREPARING,
					item.getShippingData().getIsUnipassDone());
				orderLineItemRepository.save(item);
			}
		}
		log.info("Order {} confirmed and status updated to PREPARING", id);
	}

	@Transactional
	public java.util.Map<String, Object> bulkConfirmOrders(java.util.List<Long> ids) {
		int successCount = 0;
		java.util.List<Long> failedIds = new java.util.ArrayList<>();
		java.util.List<String> errors = new java.util.ArrayList<>();

		for (Long id : ids) {
			try {
				confirmOrder(id);
				successCount++;
			} catch (Exception e) {
				failedIds.add(id);
				errors.add("Order " + id + ": " + e.getMessage());
				log.warn("Failed to confirm order {}: {}", id, e.getMessage());
			}
		}

		java.util.Map<String, Object> result = new java.util.HashMap<>();
		result.put("successCount", successCount);
		result.put("failedCount", failedIds.size());
		result.put("failedIds", failedIds);
		if (!errors.isEmpty()) {
			result.put("errors", errors);
		}
		return result;
	}

	private boolean isOrderFullyPrepared(Order order) {
		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		return !items.isEmpty() && items.stream().allMatch(item -> {
			ShippingStatus status = item.getShippingData() != null
				? item.getShippingData().getShippingStatus() : null;
			return status == ShippingStatus.PREPARING || status == ShippingStatus.SHIPPED
				|| status == ShippingStatus.DELIVERED;
		});
	}

	private void callMarketplaceAcceptApi(Order order, MarketCredential credential) {
		MarketOrderPort port = getPort(order.getMarketType());
		port.acceptOrders(credential, order);
	}

	@Transactional
	public void cancelOrder(Long id) {
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		for (OrderLineItem item : items) {
			if (item.getShippingData() != null) {
				item.updateShipping(item.getShippingData().getTrackingNo(),
					com.sbshop.agent.core.domain.order.enums.ShippingStatus.CANCELED,
					item.getShippingData().getIsUnipassDone());
				orderLineItemRepository.save(item);
			}
		}
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

		if (sourcingAccount != null && !sourcingAccount.isEmpty()) {
			item.updateSourcingForIherb(sourcingAccount, sourcingOrderNo, discountCode);
		} else {
			item.updateSourcingForVendor(sourcingVendor, sourcingOrderNo);
		}

		item.markAsPurchased();
		orderLineItemRepository.save(item);

		log.info("LineItem {} marked as PURCHASED (vendor: {}, orderNo: {})",
			lineItemId, sourcingVendor != null ? sourcingVendor : "IHB", sourcingOrderNo);
	}

	@Transactional
	public void markAsPurchasedWithAmount(Long lineItemId, String sourcingAccount,
		String sourcingOrderNo, String discountCode, String sourcingVendor,
		java.math.BigDecimal emailAmount) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PREPARING) {
			throw new IllegalStateException(
				"구매 처리는 PREPARING 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		if (sourcingAccount != null && !sourcingAccount.isEmpty()) {
			item.updateSourcingForIherb(sourcingAccount, sourcingOrderNo, discountCode);
		} else {
			item.updateSourcingForVendor(sourcingVendor, sourcingOrderNo);
		}

		if (emailAmount != null) {
			com.sbshop.agent.core.domain.order.vo.SourcingData current = item.getSourcingData();
			com.sbshop.agent.core.domain.order.vo.SourcingData updated = (current != null ? current
				: com.sbshop.agent.core.domain.order.vo.SourcingData.builder().build())
				.toBuilder()
				.sourcingAmount(emailAmount)
				.build();
			item.updateSourcingData(updated);
			log.info("이메일에서 결제금액 자동 기록: itemId={}, amount={}", lineItemId, emailAmount);
		}

		item.markAsPurchased();
		orderLineItemRepository.save(item);

		log.info("LineItem {} marked as PURCHASED (vendor: {}, orderNo: {}, emailAmount: {})",
			lineItemId, sourcingVendor != null ? sourcingVendor : "IHB", sourcingOrderNo, emailAmount);
	}

	@Transactional
	public void updateSourcingAmount(Long lineItemId, java.math.BigDecimal amount) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		com.sbshop.agent.core.domain.order.vo.SourcingData current = item.getSourcingData();
		com.sbshop.agent.core.domain.order.vo.SourcingData updated = (current != null ? current
			: com.sbshop.agent.core.domain.order.vo.SourcingData.builder().build())
			.toBuilder()
			.sourcingAmount(amount)
			.build();
		item.updateSourcingData(updated);
		orderLineItemRepository.save(item);

		log.info("LineItem {} 실구매가 업데이트: {}", lineItemId, amount);
	}

	@Transactional
	public void processShipping(Long lineItemId, String trackingNo,
		com.sbshop.agent.core.domain.order.enums.ShippingCarrier carrier) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.PURCHASED) {
			throw new IllegalStateException("배송 처리는 PURCHASED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		item.updateTrackingInfo(trackingNo, carrier);
		item.updateShippingStatus(ShippingStatus.SHIPPED);
		orderLineItemRepository.save(item);

		syncTrackingToMarketplace(item);

		log.info("LineItem {} shipped: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

	@Transactional
	public void updateTrackingInfo(Long lineItemId, String trackingNo,
		com.sbshop.agent.core.domain.order.enums.ShippingCarrier carrier) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.SHIPPED) {
			throw new IllegalStateException("송장 수정은 SHIPPED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		item.updateTrackingInfo(trackingNo, carrier);
		orderLineItemRepository.save(item);

		syncTrackingToMarketplace(item);

		log.info("LineItem {} tracking updated: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}
}
