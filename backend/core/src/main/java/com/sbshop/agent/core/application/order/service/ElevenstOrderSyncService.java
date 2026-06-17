package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElevenstOrderSyncService {

	private final MarketCredentialRepository credentialRepository;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final ProductRepository productRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final ElevenstOrderAdapter elevenstOrderAdapter;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncElevenstOrders() {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[ELEVEN_STREET] 동기화 중복 실행 방지");
			return;
		}

		try {
			MarketCredential credential = loadAndValidateCredential();
			List<MarketOrderDto> orders = elevenstOrderAdapter.fetchOrders(
				credential, LocalDate.now().minusDays(30), LocalDate.now());

			processOrders(orders, credential);
			postSyncProcess(orders);

			log.info("[ELEVEN_STREET] 주문 동기화 완료: {}건 처리", orders.size());
		} catch (Exception e) {
			log.error("[ELEVEN_STREET] 주문 동기화 실패: {}", e.getMessage(), e);
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, MarketType.ELEVEN_STREET, false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.ELEVEN_STREET));
		}
	}

	private MarketCredential loadAndValidateCredential() {
		MarketCredential credential = credentialRepository.findByMarketType(MarketType.ELEVEN_STREET)
			.orElseThrow(() -> new IllegalArgumentException("ELEVEN_STREET 크레덴셜 없음"));

		if (credential.getAccessKey() == null || credential.getAccessKey().isEmpty()) {
			throw new IllegalArgumentException("11번가 크레덴셜 불완전: API Key 필요");
		}
		return credential;
	}

	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential) {
		for (MarketOrderDto dto : marketOrders) {
			log.info("[ELEVEN_STREET] 처리 중: orderNo={}, status={}", dto.getMarketOrderNo(), dto.getStatus());
			Optional<Order> existingOrder = orderRepository.findByMarketOrderNo(dto.getMarketOrderNo());

			if (existingOrder.isPresent()) {
				log.info("[ELEVEN_STREET] 기존 주문 발견: id={}, orderNo={}",
					existingOrder.get().getId(), dto.getMarketOrderNo());
				updateExistingOrder(existingOrder.get(), dto);
			} else {
				log.info("[ELEVEN_STREET] 신규 주문 생성 시도: orderNo={}", dto.getMarketOrderNo());
				createNewOrder(dto);
			}
		}
	}

	private void updateExistingOrder(Order order, MarketOrderDto dto) {
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());

		for (OrderLineItem item : lineItems) {
			updateLineItemFromDto(item, dto);
		}

		updateOrderInfoFromDto(order, dto);
		orderRepository.save(order);
		lineItems.forEach(orderLineItemRepository::save);
	}

	private void updateLineItemFromDto(OrderLineItem item, MarketOrderDto dto) {
		Long productId = resolveProductId(dto);
		if (productId != null && !productId.equals(item.getProductId())) {
			item.assignProductId(productId);
		}
		item.updateShippingInfo(
			dto.getTrackingNo(),
			dto.getStatus(),
			item.getShippingData() != null ? item.getShippingData().getIsUnipassDone() : null,
			dto.getCarrier(),
			null);
	}

	private void updateOrderInfoFromDto(Order order, MarketOrderDto dto) {
		order.update(
			dto.getRecipientName(), dto.getRecipientPhone(), dto.getZipcode(), dto.getAddress(), dto.getMessage(),
			dto.getOrdererName(), dto.getOrdererPhone(), dto.getShipmentBoxId(),
			dto.getMarketType() != null && dto.getMarketType() != order.getMarketType() ? dto.getMarketType() : null);
		if (dto.getCustomsClearanceNo() != null) {
			order.updateCustomsClearanceNo(dto.getCustomsClearanceNo());
		}
	}

	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[ELEVEN_STREET] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());

		OrderLineItem lineItem = buildLineItemFromDto(dto, order.getId());
		orderLineItemRepository.save(lineItem);
	}

	private Order buildOrderFromDto(MarketOrderDto dto) {
		return Order.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo(dto.getMarketOrderNo())
			.orderDate(dto.getOrderDate())
			.recipientName(dto.getRecipientName())
			.recipientPhone(dto.getRecipientPhone())
			.zipcode(dto.getZipcode())
			.address(dto.getAddress())
			.message(dto.getMessage())
			.customsData(buildCustomsData(dto))
			.ordererName(dto.getOrdererName())
			.ordererPhone(dto.getOrdererPhone())
			.shipmentBoxId(dto.getShipmentBoxId())
			.build();
	}

	private OrderLineItem buildLineItemFromDto(MarketOrderDto dto, Long orderId) {
		Long productId = resolveProductId(dto);

		return OrderLineItem.builder()
			.orderId(orderId)
			.productId(productId)
			.quantity(dto.getQuantity())
			.shippingData(ShippingData.builder()
				.trackingNo(dto.getTrackingNo())
				.shippingStatus(dto.getStatus())
				.shippingCarrier(dto.getCarrier())
				.build())
			.settlementData(SettlementData.builder()
				.settlementAmount(dto.getTotalAmount())
				.settlementVerified(false)
				.build())
			.build();
	}

	private Long resolveProductId(MarketOrderDto dto) {
		if (dto.getMarketProductCode() != null) {
			Product product = productRepository.findBySbCode(dto.getMarketProductCode()).orElse(null);
			return product != null ? product.getId() : null;
		}
		return null;
	}

	private CustomsData buildCustomsData(MarketOrderDto dto) {
		if (dto.getCustomsClearanceNo() != null && !dto.getCustomsClearanceNo().trim().isEmpty()) {
			return CustomsData.builder()
				.customsClearanceNo(dto.getCustomsClearanceNo())
				.build();
		}
		return null;
	}

	private void postSyncProcess(List<MarketOrderDto> orders) {}
}
