package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.application.order.adapter.EsmplusOrderAdapter;
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
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class EsmplusOrderSyncService {

	private final MarketCredentialRepository credentialRepository;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final ProductRepository productRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final EsmplusOrderAdapter esmplusOrderAdapter;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncEsmplusOrders() {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[ESMPLUS] 동기화 중복 실행 방지");
			return;
		}

		boolean success = false;
		try {
			MarketCredential credential = loadAndValidateCredential();
			List<MarketOrderDto> orders = esmplusOrderAdapter.fetchOrders(
				credential, LocalDate.now().minusDays(30), LocalDate.now());

			processOrders(orders, credential);
			postSyncProcess(orders);

			log.info("[ESMPLUS] 주문 동기화 완료: {}건 처리", orders.size());
			success = true;
		} catch (Exception e) {
			log.error("[ESMPLUS] 주문 동기화 실패: {}", e.getMessage(), e);
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, MarketType.GMARKET, false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			if (success) {
				eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.GMARKET));
			}
		}
	}

	private MarketCredential loadAndValidateCredential() {
		MarketCredential credential = credentialRepository.findByMarketType(MarketType.GMARKET)
			.orElseThrow(() -> new IllegalArgumentException("GMARKET 크레덴셜 없음"));

		// D-043: 빈 문자열/공백도 불완전으로 fast-fail (masterId EMPTY를 스크래핑 이전에 명확히 실패).
		if (!StringUtils.hasText(credential.getAccessKey())) {
			throw new IllegalArgumentException("ESM+ 크레덴셜 불완전: masterId(access-key) 확인");
		}
		// D-045: 비밀번호(secret-key)도 검증 — 빈 비밀번호로 Selenium 로그인이 조용히 실패("성공 0건" 위장)하던 것을
		// 스크래핑 이전에 명확히 실패시킨다.
		if (!StringUtils.hasText(credential.getSecretKey())) {
			throw new IllegalArgumentException("ESM+ 크레덴셜 불완전: 비밀번호(secret-key) 확인");
		}
		return credential;
	}

	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential) {
		for (MarketOrderDto dto : marketOrders) {
			log.info("[ESMPLUS] 처리 중: orderNo={}, status={}", dto.getMarketOrderNo(), dto.getStatus());
			Optional<Order> existingOrder = orderRepository.findByMarketOrderNo(dto.getMarketOrderNo());

			if (existingOrder.isPresent()) {
				log.info("[ESMPLUS] 기존 주문 발견: id={}, orderNo={}",
					existingOrder.get().getId(), dto.getMarketOrderNo());
				updateExistingOrder(existingOrder.get(), dto);
			} else {
				if (dto.getStatus() == ShippingStatus.CANCELED || dto.getStatus() == ShippingStatus.EXCHANGED) {
					log.info("[ESMPLUS] 미동기화 취소/교환 주문 - 신규 생성 건너뜀: orderNo={}, status={}",
						dto.getMarketOrderNo(), dto.getStatus());
					continue;
				}
				log.info("[ESMPLUS] 최소 데이터 주문 - 상세 조회 시도: orderNo={}", dto.getMarketOrderNo());
				MarketOrderDto fullDto = esmplusOrderAdapter.fetchOrderDetail(credential, dto);
				if (fullDto != null) {
					log.info("[ESMPLUS] 상세 조회 성공 - 신규 주문 생성: orderNo={}", dto.getMarketOrderNo());
					createNewOrder(fullDto);
				} else {
					log.warn("[ESMPLUS] 상세 조회 실패 - 기본 데이터로 신규 주문 생성: orderNo={}",
						dto.getMarketOrderNo());
					createNewOrder(dto);
				}
			}
		}
	}

	private void updateExistingOrder(Order order, MarketOrderDto dto) {
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());

		for (OrderLineItem item : lineItems) {
			updateLineItemFromDto(item, dto);
		}

		updateOrderInfoFromDto(order, dto, lineItems);
		orderRepository.save(order);
		lineItems.forEach(orderLineItemRepository::save);
	}

	private void updateLineItemFromDto(OrderLineItem item, MarketOrderDto dto) {
		Long productId = resolveProductId(dto);
		if (productId != null && !productId.equals(item.getProductId())) {
			item.assignProductId(productId);
		}
		ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
			.trackingNo(dto.getTrackingNo())
			.shippingCarrier(dto.getCarrier())
			.shippingStatus(dto.getStatus())
			.build();
		item.applyShippingData(cmd.toShippingData(item.getShippingData()));
	}

	private void updateOrderInfoFromDto(Order order, MarketOrderDto dto, List<OrderLineItem> lineItems) {
		// D-074: 진행(PREPARING 이상) lineItem 존재 시 주소·우편번호를 API 값으로 덮지 않음(수기 보정 보호, 세트).
		boolean protectAddress = lineItems.stream().anyMatch(OrderLineItem::isProgressed);
		order.update(
			dto.getRecipientName(), dto.getRecipientPhone(),
			protectAddress ? null : dto.getZipcode(), protectAddress ? null : dto.getAddress(), dto.getMessage(),
			dto.getOrdererName(), dto.getOrdererPhone(), dto.getShipmentBoxId(),
			dto.getMarketType() != null && dto.getMarketType() != order.getMarketType() ? dto.getMarketType() : null);
		if (dto.getCustomsClearanceNo() != null) {
			order.updateCustomsClearanceNo(dto.getCustomsClearanceNo());
		}
	}

	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[ESMPLUS] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());

		OrderLineItem lineItem = buildLineItemFromDto(dto, order.getId());
		orderLineItemRepository.save(lineItem);
	}

	private Order buildOrderFromDto(MarketOrderDto dto) {
		return Order.builder()
			.marketType(MarketType.GMARKET)
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
		return CustomsData.builder()
			.customsClearanceNo(dto.getCustomsClearanceNo())
			.build();
	}

	private void postSyncProcess(List<MarketOrderDto> orders) {}
}
