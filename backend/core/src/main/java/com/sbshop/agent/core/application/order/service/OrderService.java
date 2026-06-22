package com.sbshop.agent.core.application.order.service;

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

	/** 주문 검색 */
	public Page<OrderDetailDto> searchOrders(OrderSearchCondition condition,
		Pageable pageable) {
		return orderRepository.searchOrderGrid(condition, pageable);
	}

	/** 주소/통관번호 사용자 수정 (NEW 상태 시 차단) @reviewed */
	@Transactional
	public Order updateOrder(Long id, OrderUpdateCommand command) {
		Order order = orderRepository
			.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		// NEW 상태일 때 address, customsClearanceNo 수정 차단
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

		return order;
	}

	/** 유니패스완료여부 사용자 수정 (NEW 상태 시 차단) @reviewed */
	@Transactional
	public OrderLineItem updateOrderLineItem(Long id, OrderLineItemUpdateCommand command) {
		OrderLineItem lineItem = orderLineItemRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + id));

		ShippingStatus currentStatus = lineItem.getShippingData() != null
			? lineItem.getShippingData().getShippingStatus() : null;

		if (currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.UNKNOWN) {
			throw new IllegalStateException("발주확인 전에는 라인아이템 정보를 수정할 수 없습니다.");
		}

		if (command.getIsUnipassDone() != null) {
			lineItem.updateUnipassDone(command.getIsUnipassDone());
		}

		return orderLineItemRepository.save(lineItem);
	}

	/** 주문정보 수정 */
	@Transactional
	public OrderLineItem updateSourcingInfo(Long lineItemId, SourcingUpdateCommand command) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;

		if (currentStatus == ShippingStatus.NEW) {
			throw new IllegalStateException("발주확인 전에는 소싱 정보를 수정할 수 없습니다.");
		}

		if (currentStatus == ShippingStatus.PREPARING) {
			// 구매 처리 (PREPARING -> PURCHASED)
			if (command.getSourcingOrderNo() == null || command.getSourcingOrderNo().isEmpty()) {
				throw new IllegalStateException("소싱 시 주문번호는 필수입니다.");
			}
			item.applySourcingData(command.toSourcingData(item.getSourcingData()));
			item.markAsPurchased();
			orderLineItemRepository.save(item);

			log.info("라인아이템 {} PURCHASED로 변경 (vendor: {}, orderNo: {})",
				lineItemId, command.getSourcingVendor(), command.getSourcingOrderNo());
		} else {
			// 단순 정보 수정 (구매완료 이후 상태)
			item.applySourcingData(command.toSourcingData(item.getSourcingData()));
			orderLineItemRepository.save(item);

			log.info("라인아이템 {} 소싱 정보 업데이트 완료", lineItemId);
		}
		return item;
	}

	@Transactional
	public OrderLineItem updateShippingInfo(Long lineItemId, ShippingUpdateCommand command) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;

		if (currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.PREPARING) {
			throw new IllegalStateException("발주확인 또는 구매완료 전에는 배송 정보를 수정할 수 없습니다.");
		}

		if (currentStatus == ShippingStatus.PURCHASED) {
			// 배송 처리 (PURCHASED -> SHIPPED)
			item.applyShippingData(command.toShippingData(item.getShippingData()));
			item.markAsShipped();
			orderLineItemRepository.save(item);

			marketplaceShippingService.sendTrackingToMarketplace(item);
			item.markTrackingAsSent();
			orderLineItemRepository.save(item);
			log.info("라인아이템 {} 배송 처리: tracking={}, carrier={}", lineItemId, command.getTrackingNo(),
				command.getShippingCarrier());
		} else {
			// 송장 수정 (SHIPPED 이후 상태)
			item.applyShippingData(command.toShippingData(item.getShippingData()));
			orderLineItemRepository.save(item);

			marketplaceShippingService.sendTrackingToMarketplace(item);
			item.markTrackingAsSent();
			orderLineItemRepository.save(item);
			log.info("라인아이템 {} 송장번호 업데이트: tracking={}, carrier={}", lineItemId,
				command.getTrackingNo(), command.getShippingCarrier());
		}
		return item;
	}

	@Transactional
	public void deleteOrder(Long id) {
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(id);
		orderLineItemRepository.deleteAll(lineItems);
		orderRepository.deleteById(id);
	}

	@Transactional
	public Order confirmOrder(Long id) {
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		if (order.getMarketType() == null) {
			throw new IllegalStateException("Market type is not set for order: " + id);
		}

		if (isOrderFullyPrepared(order)) {
			return order;
		}

		MarketCredential credential = credentialRepository.findByMarketType(order.getMarketType())
			.orElseThrow(() -> new RuntimeException(order.getMarketType() + " credentials not found"));

		try {
			callMarketplaceAcceptApi(order, credential);
		} catch (Exception e) {
			log.error("마켓플레이스 주문 접수 API 실패: order={} ({}): {}",
				id, order.getMarketOrderNo(), e.getMessage());
			throw new RuntimeException("마켓플레이스 주문 접수 실패: " + e.getMessage(), e);
		}

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

	@Transactional
	public BulkConfirmResult bulkConfirmOrders(java.util.List<Long> ids) {
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
		MarketOrderPort port = marketplaceShippingService.getPort(order.getMarketType());
		port.acceptOrders(credential, order);
	}

	@Transactional
	public Order cancelOrder(Long id) {
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

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
		item.markAsPurchased();
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} PURCHASED로 변경 (vendor: {}, orderNo: {})",
			lineItemId, sourcingVendor, sourcingOrderNo);
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

	@Transactional
	public void updateSourcingAmount(Long lineItemId, java.math.BigDecimal amount) {
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
		if (currentStatus != ShippingStatus.PURCHASED) {
			throw new IllegalStateException("배송 처리는 PURCHASED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

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

		marketplaceShippingService.sendTrackingToMarketplace(item);
		item.markTrackingAsSent();
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} 배송 처리: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

	@Transactional
	public void updateTrackingInfo(Long lineItemId, String trackingNo,
		ShippingCarrier carrier) {
		OrderLineItem item = orderLineItemRepository.findById(lineItemId)
			.orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

		ShippingStatus currentStatus = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (currentStatus != ShippingStatus.SHIPPED) {
			throw new IllegalStateException("송장 수정은 SHIPPED 상태에서만 가능합니다. 현재: " + currentStatus);
		}

		ShippingData currentShipping = item.getShippingData();
		if (currentShipping == null) {
			currentShipping = ShippingData.builder().build();
		}
		item.applyShippingData(currentShipping.toBuilder()
			.trackingNo(trackingNo)
			.shippingCarrier(carrier)
			.build());
		orderLineItemRepository.save(item);

		marketplaceShippingService.sendTrackingToMarketplace(item);
		item.markTrackingAsSent();
		orderLineItemRepository.save(item);

		log.info("라인아이템 {} 송장번호 업데이트: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
	}

}
