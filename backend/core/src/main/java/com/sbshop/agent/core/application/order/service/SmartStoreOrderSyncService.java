package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.application.order.adapter.SmartStoreOrderAdapter;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
public class SmartStoreOrderSyncService {
	private final MarketCredentialRepository credentialRepository;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final ProductRepository productRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final SmartStoreOrderAdapter smartStoreOrderAdapter;
	private final SyncStatusService syncStatusService;
	private final MarketFeeService marketFeeService;
	private final TerminalSettlementService terminalSettlementService;
	private final MarketLineItemSyncDispatcher lineItemSyncDispatcher;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncSmartStoreOrders() {
		syncSmartStoreOrders(30);
	}

	public void syncSmartStoreOrders(int lookbackDays) {
		syncSmartStoreOrders(LocalDate.now().minusDays(lookbackDays), LocalDate.now());
	}

	public void syncSmartStoreOrders(LocalDate fromDate, LocalDate toDate) {
		syncSmartStoreOrders(fromDate, toDate, true);
	}

	@Transactional
	public void syncSmartStoreOrders(LocalDate fromDate, LocalDate toDate, boolean createMissing) {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[SMART_STORE] 동기화 중복 실행 방지");
			return;
		}

		syncStatusService.markRunning(SyncMarketKeys.SMART_STORE);
		boolean success = false;
		try {
			MarketCredential credential = loadAndValidateCredential();
			List<MarketOrderDto> orders = smartStoreOrderAdapter.fetchOrders(
				credential, fromDate, toDate);

			processOrders(orders, credential, createMissing);
			postSyncProcess(orders);

			log.info("[SMART_STORE] 주문 동기화 완료: {}건 처리", orders.size());
			success = true;
			syncStatusService.markCompleted(SyncMarketKeys.SMART_STORE);
		} catch (Exception e) {
			log.error("[SMART_STORE] 주문 동기화 실패: {}", e.getMessage(), e);
			syncStatusService.markFailed(
				SyncMarketKeys.SMART_STORE, e.getMessage());
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, MarketType.SMART_STORE, false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			if (success) {
				eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.SMART_STORE));
			}
		}
	}

	private MarketCredential loadAndValidateCredential() {
		MarketCredential credential = credentialRepository.findByMarketType(MarketType.SMART_STORE)
			.orElseThrow(() -> new IllegalArgumentException("SMART_STORE 크레덴셜 없음"));

		if (!StringUtils.hasText(credential.getClientId())
			|| !StringUtils.hasText(credential.getSecretKey())) {
			throw new IllegalArgumentException("스마트스토어 크레덴셜 불완전: client-id/secret-key 확인");
		}
		return credential;
	}

	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential,
		boolean createMissing) {
		marketOrders = marketOrders.stream().map(MarketOrderNormalizer::normalize).toList();
		for (MarketOrderDto dto : marketOrders) {
			log.info("[SMART_STORE] 처리 중: orderNo={}, status={}", dto.getMarketOrderNo(), dto.getStatus());
			Order existing = findExistingOrder(dto);
			if (existing != null) {
				log.info("[SMART_STORE] 기존 주문 발견: id={}, orderNo={}", existing.getId(), dto.getMarketOrderNo());
				updateExistingOrder(existing, dto);
			} else if (createMissing) {
				log.info("[SMART_STORE] 신규 주문 생성 시도: orderNo={}", dto.getMarketOrderNo());
				createNewOrder(dto);
			} else {
				log.debug("[SMART_STORE] 갱신 전용 모드 — 없는 주문은 만들지 않는다: orderNo={}",
					dto.getMarketOrderNo());
			}
		}
	}

	private Order findExistingOrder(MarketOrderDto dto) {
		String newKey = dto.getMarketOrderNo();
		if (newKey == null || newKey.isBlank()) {
			return null;
		}
		Order byNewKey = orderRepository.findByMarketOrderNo(newKey).orElse(null);
		if (byNewKey != null) {
			return byNewKey;
		}

		Order rekeyed = null;
		for (String legacyKey : legacyOrderKeys(dto)) {
			Order legacy = orderRepository.findByMarketOrderNo(legacyKey).orElse(null);
			if (legacy == null) {
				continue;
			}
			if (rekeyed == null) {
				legacy.rekeyMarketOrderNo(newKey);
				orderRepository.save(legacy);
				rekeyed = legacy;
				log.info("[SMART_STORE] 주문 키 이전: {} → {} (id={})", legacyKey, newKey, legacy.getId());
				continue;
			}
			log.warn("[SMART_STORE] ⚠ 확인 필요: 주문 {}의 옛 행이 더 있다 — id={}, 옛 키={}."
				+ " 라인아이템을 id={}로 옮기고 이 행을 정리해야 한다",
				newKey, legacy.getId(), legacyKey, rekeyed.getId());
		}
		return rekeyed;
	}

	private List<String> legacyOrderKeys(MarketOrderDto dto) {
		if (dto.getShipments() == null) {
			return List.of();
		}
		return dto.getShipments().stream()
			.filter(shipment -> shipment.getLineItems() != null)
			.flatMap(shipment -> shipment.getLineItems().stream())
			.map(MarketLineItemDto::getMarketLineItemNo)
			.filter(key -> key != null && !key.isBlank() && !key.equals(dto.getMarketOrderNo()))
			.distinct()
			.toList();
	}

	private void updateExistingOrder(Order order, MarketOrderDto dto) {
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
		updateOrderInfoFromDto(order, dto, lineItems);
		orderRepository.save(order);
		lineItemSyncDispatcher.sync(order, dto, lineItems, syncPolicy);
	}

	private void updateOrderInfoFromDto(Order order, MarketOrderDto dto, List<OrderLineItem> lineItems) {
		boolean protectAddress = lineItems.stream().anyMatch(OrderLineItem::isProgressed);
		order.update(
			dto.getRecipientName(), dto.getRecipientPhone(),
			protectAddress ? null : dto.getZipcode(), protectAddress ? null : dto.getAddress(), dto.getMessage(),
			dto.getOrdererName(), dto.getOrdererPhone(),
			dto.getMarketType() != null && dto.getMarketType() != order.getMarketType() ? dto.getMarketType() : null);
		order.applyCustomsClearanceNoFromMarket(dto.getCustomsClearanceNo());
		applyMarketSpecificData(order, dto);
	}

	private void applyMarketSpecificData(Order order, MarketOrderDto dto) {
		if (dto.getMarketSpecificData() == null || dto.getMarketSpecificData().isEmpty()) {
			return;
		}
		Map<String, String> stringMap = new HashMap<>();
		for (Map.Entry<String, Object> entry : dto.getMarketSpecificData().entrySet()) {
			stringMap.put(entry.getKey(), String.valueOf(entry.getValue()));
		}
		order.setMarketSpecificDataFromMap(stringMap);
	}

	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[SMART_STORE] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());
		lineItemSyncDispatcher.sync(order, dto, List.of(), syncPolicy);
	}

	private Order buildOrderFromDto(MarketOrderDto dto) {
		Order order = Order.builder()
			.marketType(MarketType.SMART_STORE)
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
			.build();
		applyMarketSpecificData(order, dto);
		return order;
	}

	private final MarketLineItemSyncPolicy syncPolicy = new MarketLineItemSyncPolicy() {
		@Override
		public String logTag() {
			return "SMART_STORE";
		}

		@Override
		public Long resolveProductId(MarketLineItemDto dto) {
			return smartStoreResolveProductId(dto);
		}

		@Override
		public OrderLineItem createLineItem(MarketLineItemDto dto, Long orderId, Long productId) {
			return buildLineItemFromDto(dto, orderId, productId);
		}

		@Override
		public BigDecimal settlementAmount(MarketLineItemDto dto) {
			return resolveSettlementAmount(dto);
		}
	};

	private CustomsData buildCustomsData(MarketOrderDto dto) {
		String customsNo = dto.getCustomsClearanceNo();
		if ("undefined".equals(customsNo)) {
			customsNo = null;
		}

		return CustomsData.builder()
			.customsClearanceNo(customsNo)
			.build();
	}

	private void postSyncProcess(List<MarketOrderDto> orders) {
		terminalSettlementService.zeroSettlementForRefunded(MarketType.SMART_STORE);
	}

	private OrderLineItem buildLineItemFromDto(MarketLineItemDto dto, Long orderId, Long productId) {
		return OrderLineItem.builder()
			.orderId(orderId)
			.productId(productId)
			.quantity(dto.getQuantity() != null ? dto.getQuantity() : 0)
			.marketLineItemNo(dto.getMarketLineItemNo())
			.shippingData(ShippingData.builder()
				.shippingStatus(dto.getStatus())
				.build())
			.settlementData(SettlementData.builder()
				.settlementAmount(resolveSettlementAmount(dto))
				.settlementVerified(false)
				.build())
			.build();
	}

	private BigDecimal resolveSettlementAmount(MarketLineItemDto dto) {
		if (dto.getSettlementAmount() != null && dto.getSettlementAmount().signum() != 0) {
			return dto.getSettlementAmount();
		}
		return marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.SMART_STORE);
	}

	private Long smartStoreResolveProductId(MarketLineItemDto dto) {
		if (dto.getMarketProductCode() != null) {
			Product product = productRepository.findBySbCode(dto.getMarketProductCode()).orElse(null);
			return product != null ? product.getId() : null;
		}
		return null;
	}
}
