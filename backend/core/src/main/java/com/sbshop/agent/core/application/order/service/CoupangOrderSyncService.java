package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.math.BigDecimal;
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
public class CoupangOrderSyncService {

	private final MarketCredentialRepository credentialRepository;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final ProductRepository productRepository;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final CoupangOrderAdapter coupangOrderAdapter;
	private final CoupangStatusMapper statusMapper;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangOrders() {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[COUPANG] 동기화 중복 실행 방지");
			return;
		}

		try {
			MarketCredential credential = loadAndValidateCredential();
			List<MarketOrderDto> orders = coupangOrderAdapter.fetchOrders(
				credential, LocalDate.now().minusDays(30), LocalDate.now());

			processOrders(orders, credential);
			postSyncProcess(orders);

			log.info("[COUPANG] 주문 동기화 완료: {}건 처리", orders.size());
		} catch (Exception e) {
			log.error("[COUPANG] 주문 동기화 실패: {}", e.getMessage(), e);
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, MarketType.COUPANG, false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.COUPANG));
		}
	}

	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangSettlement() {
		try {
			MarketCredential credential = loadAndValidateCredential();

			LocalDate fromDate = LocalDate.now().minusDays(31);
			LocalDate toDate = LocalDate.now().minusDays(1);

			log.info("쿠팡 정산 동기화 시작: {} ~ {}", fromDate, toDate);

			java.util.Map<String, BigDecimal> settlementMap = coupangOrderAdapter.querySettlement(
				credential, fromDate, toDate);

			if (settlementMap.isEmpty()) {
				log.info("쿠팡 정산 데이터 없음");
				return;
			}

			List<Order> coupangOrders = orderRepository.findByMarketType(MarketType.COUPANG);
			int updatedCount = 0;

			for (Order order : coupangOrders) {
				List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
				for (OrderLineItem item : lineItems) {
					if (item.getShippingData() == null
						|| item.getShippingData()
							.getShippingStatus() != com.sbshop.agent.core.domain.order.enums.ShippingStatus.DELIVERED) {
						continue;
					}

					if (item.getProductId() == null)
						continue;
					String sbCode = productRepository.findById(item.getProductId())
						.map(Product::getSbCode).orElse(null);
					if (sbCode == null || sbCode.isEmpty())
						continue;

					BigDecimal actualSettlement = settlementMap.get(sbCode);
					if (actualSettlement != null) {
						BigDecimal currentSettlement = item.getSettlementData() != null
							? item.getSettlementData().getSettlementAmount() : null;

						if (currentSettlement == null || actualSettlement.compareTo(currentSettlement) != 0) {
							item.updateSettlement(actualSettlement);
							item.markSettlementVerified();
							orderLineItemRepository.save(item);
							updatedCount++;
						}
					}
				}
			}

			log.info("쿠팡 정산 동기화 완료: {}건 업데이트", updatedCount);
		} catch (Exception e) {
			log.error("쿠팡 정산 동기화 실패: {}", e.getMessage());
		}
	}

	private MarketCredential loadAndValidateCredential() {
		MarketCredential credential = credentialRepository.findByMarketType(MarketType.COUPANG)
			.orElseThrow(() -> new IllegalArgumentException("COUPANG 크레덴셜 없음"));

		if (credential.getClientId() == null || credential.getAccessKey() == null
			|| credential.getSecretKey() == null) {
			throw new IllegalArgumentException("쿠팡 크레덴셜 불완전");
		}
		return credential;
	}

	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential) {
		for (MarketOrderDto dto : marketOrders) {
			log.info("[COUPANG] 처리 중: orderNo={}, status={}", dto.getMarketOrderNo(), dto.getStatus());
			Optional<Order> existingOrder = orderRepository.findByMarketOrderNo(dto.getMarketOrderNo());

			if (existingOrder.isPresent()) {
				log.info("[COUPANG] 기존 주문 발견: id={}, orderNo={}",
					existingOrder.get().getId(), dto.getMarketOrderNo());
				updateExistingOrder(existingOrder.get(), dto);
			} else {
				log.info("[COUPANG] 신규 주문 생성 시도: orderNo={}", dto.getMarketOrderNo());
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
		item.updateShippingWithCarrier(
			dto.getTrackingNo(),
			dto.getStatus(),
			item.getShippingData() != null ? item.getShippingData().getIsUnipassDone() : null,
			dto.getCarrier());
	}

	private void updateOrderInfoFromDto(Order order, MarketOrderDto dto) {
		if (dto.getMarketType() != null && dto.getMarketType() != order.getMarketType()) {
			order.updateMarketType(dto.getMarketType());
		}
		order.updateInfo(
			dto.getRecipientName(),
			dto.getRecipientPhone(),
			dto.getZipcode(),
			dto.getAddress(),
			dto.getMessage());

		if (dto.getOrdererName() != null) {
			order.updateOrdererInfo(dto.getOrdererName(), dto.getOrdererPhone());
		}

		if (dto.getCustomsClearanceNo() != null) {
			order.updateCustomsClearanceNo(dto.getCustomsClearanceNo());
		}

		if (dto.getShipmentBoxId() != null) {
			order.updateShipmentBoxId(dto.getShipmentBoxId());
		}
	}

	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[COUPANG] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());

		OrderLineItem lineItem = buildLineItemFromDto(dto, order.getId());
		orderLineItemRepository.save(lineItem);
	}

	private Order buildOrderFromDto(MarketOrderDto dto) {
		return Order.builder()
			.marketType(MarketType.COUPANG)
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

		BigDecimal settlementAmount = dto.getTotalAmount() != null
			? dto.getTotalAmount().multiply(new BigDecimal("0.89"))
			: dto.getTotalAmount();

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
				.settlementAmount(settlementAmount)
				.settlementVerified(false)
				.build())
			.build();
	}

	private Long resolveProductId(MarketOrderDto dto) {
		if (dto.getMarketProductCode() != null) {
			List<MarketRegistration> regs = marketRegistrationRepository
				.findByMarketTypeAndIdentifiersContaining(MarketType.COUPANG, dto.getMarketProductCode());
			if (!regs.isEmpty()) {
				Long sbProductId = regs.get(0).getSbProductId();
				log.info("[COUPANG] sb_market_registration에서 productId 조회: vendorItemId={}, sbProductId={}",
					dto.getMarketProductCode(), sbProductId);
				return sbProductId;
			}
			log.warn("[COUPANG] sb_market_registration에서 productId를 찾을 수 없음: vendorItemId={}",
				dto.getMarketProductCode());
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

	private void postSyncProcess(List<MarketOrderDto> orders) {
		LocalDate fromDate = LocalDate.now().minusDays(30);
		LocalDate toDate = LocalDate.now();

		coupangOrderAdapter.detectCancellations(orders, fromDate, toDate);
		coupangOrderAdapter.fixCarriers(orders);
	}
}
