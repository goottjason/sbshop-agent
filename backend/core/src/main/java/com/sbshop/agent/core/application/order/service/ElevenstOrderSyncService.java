package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.sync.SyncCounts;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
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
public class ElevenstOrderSyncService {
	private final MarketCredentialRepository credentialRepository;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final ProductRepository productRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final ElevenstOrderAdapter elevenstOrderAdapter;
	private final SyncStatusService syncStatusService;
	private final MarketFeeService marketFeeService;
	private final TerminalSettlementService terminalSettlementService;
	private final MarketLineItemSyncDispatcher lineItemSyncDispatcher;
	private final ShipmentRepository shipmentRepository;
	private final MarketRegistrationLookup marketRegistrationLookup;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncElevenstOrders() {
		syncElevenstOrders(30);
	}

	public void syncElevenstOrders(int lookbackDays) {
		syncElevenstOrders(LocalDate.now().minusDays(lookbackDays), LocalDate.now());
	}

	public void syncElevenstOrders(LocalDate fromDate, LocalDate toDate) {
		syncElevenstOrders(fromDate, toDate, true);
	}

	@Transactional
	public void syncElevenstOrders(LocalDate fromDate, LocalDate toDate, boolean createMissing) {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[ELEVEN_STREET] 동기화 중복 실행 방지");
			return;
		}

		syncStatusService.markRunning(SyncMarketKeys.ELEVEN_STREET);
		boolean success = false;
		SyncCounts completedCounts = SyncCounts.none();
		try {
			MarketCredential credential = loadAndValidateCredential();
			MarketFetchOutcome outcome = elevenstOrderAdapter
				.fetchOrdersWithOutcome(credential, fromDate, toDate);
			List<MarketOrderDto> orders = outcome.orders();

			SyncCounts counts = processOrders(orders, credential, createMissing);
			postSyncProcess(orders, credential, fromDate, toDate, createMissing, outcome.complete());

			log.info("[ELEVEN_STREET] 주문 동기화 완료: 처리 {}건, 신규 {}건",
				counts.processed(), counts.created());
			success = true;
			completedCounts = counts;
			syncStatusService.markCompleted(SyncMarketKeys.ELEVEN_STREET, counts.processed(), counts.created());
		} catch (Exception e) {
			log.error("[ELEVEN_STREET] 주문 동기화 실패: {}", e.getMessage(), e);
			syncStatusService.markFailed(
				SyncMarketKeys.ELEVEN_STREET, e.getMessage());
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, MarketType.ELEVEN_STREET, false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			if (success) {
				eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.ELEVEN_STREET,
					completedCounts.processed(), completedCounts.created()));
			}
		}
	}

	private MarketCredential loadAndValidateCredential() {
		MarketCredential credential = credentialRepository.findByMarketType(MarketType.ELEVEN_STREET)
			.orElseThrow(() -> new IllegalArgumentException("ELEVEN_STREET 크레덴셜 없음"));

		if (!StringUtils.hasText(credential.getAccessKey())) {
			throw new IllegalArgumentException("11번가 크레덴셜 불완전: API Key(access-key) 확인");
		}
		return credential;
	}

	private SyncCounts processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential,
		boolean createMissing) {
		marketOrders = marketOrders.stream().map(MarketOrderNormalizer::normalize).toList();
		return MarketOrderUpsertDispatcher.dispatch(
			marketOrders, orderRepository, "ELEVEN_STREET", this::updateExistingOrder, this::createNewOrder,
			createMissing);
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
		if (dto.getMarketSpecificData() != null && !dto.getMarketSpecificData().isEmpty()) {
			Map<String, String> stringMap = new HashMap<>();
			for (Map.Entry<String, Object> entry : dto.getMarketSpecificData().entrySet()) {
				stringMap.put(entry.getKey(), String.valueOf(entry.getValue()));
			}
			order.setMarketSpecificDataFromMap(stringMap);
		}
	}

	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[ELEVEN_STREET] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());
		lineItemSyncDispatcher.sync(order, dto, List.of(), syncPolicy);
	}

	private Order buildOrderFromDto(MarketOrderDto dto) {
		Order order = Order.builder()
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
			.build();

		if (dto.getMarketSpecificData() != null && !dto.getMarketSpecificData().isEmpty()) {
			Map<String, String> stringMap = new HashMap<>();
			for (Map.Entry<String, Object> entry : dto.getMarketSpecificData().entrySet()) {
				stringMap.put(entry.getKey(), String.valueOf(entry.getValue()));
			}
			order.setMarketSpecificDataFromMap(stringMap);
		}

		return order;
	}

	private final MarketLineItemSyncPolicy syncPolicy = new MarketLineItemSyncPolicy() {
		@Override
		public String logTag() {
			return "ELEVEN_STREET";
		}

		@Override
		public Long resolveProductId(MarketLineItemDto dto) {
			return elevenstResolveProductId(dto);
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
		return CustomsData.builder()
			.customsClearanceNo(dto.getCustomsClearanceNo())
			.build();
	}

	private void postSyncProcess(List<MarketOrderDto> orders, MarketCredential credential,
		LocalDate fromDate, LocalDate toDate, boolean createMissing, boolean fetchComplete) {
		if (!createMissing) {
			log.info("[ELEVEN_STREET] 갱신 전용 동기화 — 클레임 감지를 건너뛴다 ({}~{})", fromDate, toDate);
		} else if (!fetchComplete) {
			log.warn("[ELEVEN_STREET] 부분 조회로 클레임 감지를 건너뛴다 ({}~{}) — 못 본 주문을 사라진 것으로 읽지 않는다",
				fromDate, toDate);
		} else {
			detectClaims(orders, fromDate, toDate, credential.getAccessKey());
			applyClaimListSignals(credential.getAccessKey(), fromDate, toDate);
		}
		terminalSettlementService.zeroSettlementForRefunded(MarketType.ELEVEN_STREET);
	}

	private void applyClaimListSignals(String apiKey, LocalDate fromDate, LocalDate toDate) {
		Map<String, Map<String, ClaimData>> claimSignals = elevenstOrderAdapter.fetchClaimListSignals(apiKey, fromDate,
			toDate);
		if (claimSignals.isEmpty()) {
			return;
		}

		List<Order> dbOrders = orderRepository.findByMarketType(MarketType.ELEVEN_STREET);
		int orderCount = 0;

		for (Order order : dbOrders) {
			if (order.getOrderDate() != null) {
				LocalDate orderDate = order.getOrderDate().toLocalDate();
				if (orderDate.isBefore(fromDate) || orderDate.isAfter(toDate)) {
					continue;
				}
			}
			Map<String, ClaimData> claims = claimSignals.get(order.getMarketOrderNo());
			if (claims == null || claims.isEmpty()) {
				continue;
			}

			List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
			int applied = 0;
			for (OrderLineItem item : items) {
				ClaimData claim = resolveFor(item, claims);
				if (claim != null) {
					item.applyClaim(claim);
					orderLineItemRepository.save(item);
					applied++;
				}
			}
			if (applied > 0) {
				orderCount++;
			}
		}

		if (orderCount > 0) {
			log.info("[ELEVEN_STREET] 클레임 목록 API로 {}건 주문에 클레임 반영", orderCount);
		}
	}

	private void detectClaims(List<MarketOrderDto> apiOrders, LocalDate fromDate, LocalDate toDate, String apiKey) {
		Set<String> apiOrderNos = new HashSet<>();
		for (MarketOrderDto dto : apiOrders) {
			apiOrderNos.add(dto.getMarketOrderNo());
		}

		List<Order> dbOrders = orderRepository.findByMarketType(MarketType.ELEVEN_STREET);
		int claimCount = 0;

		for (Order order : dbOrders) {
			if (order.getOrderDate() != null) {
				LocalDate orderDate = order.getOrderDate().toLocalDate();
				if (orderDate.isBefore(fromDate) || orderDate.isAfter(toDate)) {
					continue;
				}
			}

			if (apiOrderNos.contains(order.getMarketOrderNo())) {
				continue;
			}
			List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());

			ElevenstOrderAdapter.MissingOrderState state = elevenstOrderAdapter.resolveMissingOrderState(apiKey,
				order.getMarketOrderNo());
			if (state.isEmpty()) {
				continue;
			}
			applyMarketTrackingFromMissingOrder(order, state);
			Map<String, ShippingStatus> statuses = state.statuses();
			Map<String, ClaimData> claims = state.claims();
			if (statuses.isEmpty() && claims.isEmpty()) {
				continue;
			}

			int applied = 0;
			for (OrderLineItem item : items) {
				ShippingStatus claimStatus = resolveFor(item, statuses);
				if (claimStatus != null) {
					ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
						.shippingStatus(claimStatus)
						.build();
					item.applyShippingData(cmd.toShippingData(item.getShippingData()));
					applied++;
				}
				ClaimData claim = resolveFor(item, claims);
				if (claim != null) {
					item.applyClaim(claim);
					applied++;
				}
				if (claimStatus != null || claim != null) {
					orderLineItemRepository.save(item);
				}
			}
			if (applied == 0) {
				continue;
			}
			claimCount++;
			log.info("[ELEVEN_STREET] 클레임 감지: ordNo={} → {}건 반영 상태={} 클레임={}",
				order.getMarketOrderNo(), applied, statuses, claims);
		}

		if (claimCount > 0) {
			log.info("[ELEVEN_STREET] 클레임 감지: {}건 상태 반영", claimCount);
		}
	}

	private void applyMarketTrackingFromMissingOrder(Order order,
		ElevenstOrderAdapter.MissingOrderState state) {
		if (state.trackingNos().isEmpty()) {
			return;
		}
		Set<String> distinct = new HashSet<>(state.trackingNos().values());
		if (distinct.size() != 1) {
			return;
		}
		String marketTracking = distinct.iterator().next();
		for (Shipment shipment : shipmentRepository.findByOrderId(order.getId())) {
			shipment.applyMarketTracking(marketTracking);
			shipmentRepository.save(shipment);
		}
	}

	private <T> T resolveFor(OrderLineItem item, Map<String, T> byKey) {
		String seq = item.getMarketLineItemNo();
		if (seq != null) {
			return byKey.get(seq);
		}
		T orderWide = byKey.get(ElevenstOrderAdapter.CLAIM_ORDER_WIDE);
		if (orderWide != null) {
			return orderWide;
		}
		return byKey.size() == 1 ? byKey.values().iterator().next() : null;
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
			.claimData(dto.getClaim())
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
		return marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.ELEVEN_STREET);
	}

	private Long elevenstResolveProductId(MarketLineItemDto dto) {
		if (dto.getMarketProductCode() != null) {
			Product product = productRepository.findBySbCode(dto.getMarketProductCode()).orElse(null);
			if (product != null) {
				return product.getId();
			}
			log.warn("[ELEVEN_STREET] sellerPrdCd로 상품을 찾지 못했다: {} — prdNo 폴백 시도",
				dto.getMarketProductCode());
		}
		return resolveProductIdByMarketProductNo(dto.getSellerProductId());
	}

	private Long resolveProductIdByMarketProductNo(String prdNo) {
		if (prdNo == null || prdNo.isBlank()) {
			return null;
		}
		Optional<MarketRegistration> reg = marketRegistrationLookup.findUnique(
			MarketType.ELEVEN_STREET, MarketRegistration.ELEVEN_STREET_LOOKUP_KEY, prdNo);
		if (reg.isEmpty()) {
			log.warn("[ELEVEN_STREET] prdNo로도 상품을 찾지 못했다: prdNo={}", prdNo);
			return null;
		}
		Long sbProductId = reg.get().getSbProductId();
		log.info("[ELEVEN_STREET] prdNo 폴백으로 productId 해석: prdNo={}, sbProductId={}", prdNo, sbProductId);
		return sbProductId;
	}
}
