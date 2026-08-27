package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
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
import java.util.Map;
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
	private final MarketRegistrationLookup marketRegistrationLookup;
	private final ApplicationEventPublisher eventPublisher;
	private final CoupangOrderAdapter coupangOrderAdapter;
	private final CoupangStatusMapper statusMapper;
	private final SyncStatusService syncStatusService;
	private final MarketFeeService marketFeeService;
	private final TerminalSettlementService terminalSettlementService;
	private final ActionLogService actionLogService;

	private final MarketLineItemSyncDispatcher lineItemSyncDispatcher;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangOrders() {
		syncCoupangOrders(30);
	}

	public void syncCoupangOrders(int lookbackDays) {
		syncCoupangOrders(LocalDate.now().minusDays(lookbackDays), LocalDate.now());
	}

	public void syncCoupangOrders(LocalDate fromDate, LocalDate toDate) {
		syncCoupangOrders(fromDate, toDate, true);
	}

	@Transactional
	public void syncCoupangOrders(LocalDate fromDate, LocalDate toDate, boolean createMissing) {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[COUPANG] 동기화 중복 실행 방지");
			return;
		}

		syncStatusService.markRunning(SyncMarketKeys.COUPANG);
		boolean success = false;
		try {
			MarketCredential credential = loadAndValidateCredential();
			MarketFetchOutcome outcome = coupangOrderAdapter.fetchOrdersWithOutcome(
				credential, fromDate, toDate);
			List<MarketOrderDto> orders = outcome.orders();
			processOrders(orders, credential, createMissing);
			postSyncProcess(orders, credential, fromDate, toDate, createMissing, outcome.complete());

			log.info("[COUPANG] 주문 동기화 완료: {}건 처리", orders.size());
			success = true;
			syncStatusService.markCompleted(SyncMarketKeys.COUPANG);
		} catch (Exception e) {
			log.error("[COUPANG] 주문 동기화 실패: {}", e.getMessage(), e);
			syncStatusService.markFailed(
				SyncMarketKeys.COUPANG, e.getMessage());
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, MarketType.COUPANG, false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			if (success) {
				eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.COUPANG));
			}
		}
	}

	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangSettlement() {
		if (!syncStatusService.tryMarkRunning(
			SyncMarketKeys.COUPANG_SETTLEMENT)) {
			log.warn("[COUPANG] 정산 동기화 중복 실행 방지 — 이미 RUNNING 상태이므로 스킵");
			return;
		}
		try {
			MarketCredential credential = loadAndValidateCredential();

			LocalDate fromDate = LocalDate.now().minusDays(31);
			LocalDate toDate = LocalDate.now().minusDays(1);

			log.info("쿠팡 정산 동기화 시작: {} ~ {}", fromDate, toDate);

			Map<String, BigDecimal> settlementMap = coupangOrderAdapter.querySettlement(
				credential, fromDate, toDate);

			if (settlementMap.isEmpty()) {
				log.info("쿠팡 정산 데이터 없음");
				syncStatusService.markCompleted(
					SyncMarketKeys.COUPANG_SETTLEMENT);
				recordSettlement(ActionStatus.SUCCESS,
					"쿠팡 정산 동기화 완료: 대상 없음");
				return;
			}

			List<Order> coupangOrders = orderRepository.findByMarketType(MarketType.COUPANG);
			int updatedCount = 0;

			for (Order order : coupangOrders) {
				List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
				for (OrderLineItem item : lineItems) {
					if (item.getShippingData() == null
						|| item.getShippingData()
							.getShippingStatus() != ShippingStatus.DELIVERED) {
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
							item.applySettlement(actualSettlement);
							item.markSettlementVerified();
							orderLineItemRepository.save(item);
							updatedCount++;
						}
					}
				}
			}

			log.info("쿠팡 정산 동기화 완료: {}건 업데이트", updatedCount);
			syncStatusService.markCompleted(
				SyncMarketKeys.COUPANG_SETTLEMENT);
			recordSettlement(ActionStatus.SUCCESS,
				"쿠팡 정산 동기화 완료: " + updatedCount + "건 업데이트");
		} catch (Exception e) {
			log.error("쿠팡 정산 동기화 실패: {}", e.getMessage());
			syncStatusService.markFailed(
				SyncMarketKeys.COUPANG_SETTLEMENT, e.getMessage());
			recordSettlement(ActionStatus.FAILED,
				"쿠팡 정산 동기화 실패: " + e.getMessage());
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

	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential,
		boolean createMissing) {
		marketOrders = marketOrders.stream().map(MarketOrderNormalizer::normalize).toList();
		MarketOrderUpsertDispatcher.dispatch(
			marketOrders, orderRepository, "COUPANG", this::updateExistingOrder, this::createNewOrder,
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
			dto.getRecipientName(),
			dto.getRecipientPhone(),
			protectAddress ? null : dto.getZipcode(),
			protectAddress ? null : dto.getAddress(),
			dto.getMessage(),
			dto.getOrdererName(),
			dto.getOrdererPhone(),
			dto.getMarketType());
		order.applyCustomsClearanceNoFromMarket(dto.getCustomsClearanceNo());
	}

	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[COUPANG] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());
		lineItemSyncDispatcher.sync(order, dto, List.of(), syncPolicy);
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
			.build();
	}

	private CustomsData buildCustomsData(MarketOrderDto dto) {
		return CustomsData.builder()
			.customsClearanceNo(dto.getCustomsClearanceNo())
			.build();
	}

	private final MarketLineItemSyncPolicy syncPolicy = new MarketLineItemSyncPolicy() {
		@Override
		public String logTag() {
			return "COUPANG";
		}

		@Override
		public Long resolveProductId(MarketLineItemDto dto) {
			return coupangResolveProductId(dto);
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

	private void postSyncProcess(List<MarketOrderDto> orders, MarketCredential credential,
		LocalDate fromDate, LocalDate toDate, boolean createMissing, boolean fetchComplete) {
		if (!createMissing) {
			log.info("[COUPANG] 갱신 전용 동기화 — 취소·반품 감지를 건너뛴다 ({}~{})", fromDate, toDate);
		} else if (!fetchComplete) {
			log.warn("[COUPANG] 부분 조회로 취소·반품 감지를 건너뛴다 ({}~{}) — 못 본 주문을 사라진 것으로 읽지 않는다",
				fromDate, toDate);
		} else {
			coupangOrderAdapter.detectCancellations(orders, fromDate, toDate);
			coupangOrderAdapter.detectReturns(credential, fromDate, toDate);
		}
		terminalSettlementService.zeroSettlementForRefunded(MarketType.COUPANG);
		coupangOrderAdapter.fixCarriers(orders);
	}

	private void recordSettlement(ActionStatus status,
		String message) {
		actionLogService.record(
			ActionLogConstants.COUPANG_SETTLEMENT_SYNC,
			"COUPANG", status, message);
	}

	private OrderLineItem buildLineItemFromDto(MarketLineItemDto dto, Long orderId, Long productId) {
		BigDecimal settlementAmount = resolveSettlementAmount(dto);

		return OrderLineItem.builder()
			.orderId(orderId)
			.productId(productId)
			.quantity(dto.getQuantity() != null ? dto.getQuantity() : 0)
			.marketLineItemNo(dto.getMarketLineItemNo())
			.shippingData(ShippingData.builder()
				.shippingStatus(dto.getStatus())
				.build())
			.settlementData(SettlementData.builder()
				.settlementAmount(settlementAmount)
				.settlementVerified(false)
				.build())
			.build();
	}

	private BigDecimal resolveSettlementAmount(MarketLineItemDto dto) {
		if (dto.getSettlementAmount() != null && dto.getSettlementAmount().signum() != 0) {
			return dto.getSettlementAmount();
		}
		return marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.COUPANG);
	}

	private Long coupangResolveProductId(MarketLineItemDto dto) {
		if (dto.getMarketProductCode() != null) {
			Optional<MarketRegistration> byVendorItem = marketRegistrationLookup.findUnique(
				MarketType.COUPANG, MarketRegistration.COUPANG_VENDOR_ITEM_KEY, dto.getMarketProductCode());
			if (byVendorItem.isPresent()) {
				Long sbProductId = byVendorItem.get().getSbProductId();
				log.info("[COUPANG] sb_market_registration에서 productId 조회: vendorItemId={}, sbProductId={}",
					dto.getMarketProductCode(), sbProductId);
				return sbProductId;
			}
		}

		if (dto.getSellerProductId() != null && !dto.getSellerProductId().isEmpty()) {
			Optional<MarketRegistration> bySeller = marketRegistrationLookup.findUnique(
				MarketType.COUPANG, MarketRegistration.COUPANG_LOOKUP_KEY, dto.getSellerProductId());
			if (bySeller.isPresent()) {
				MarketRegistration reg = bySeller.get();
				if (dto.getMarketProductCode() != null && !dto.getMarketProductCode().isEmpty()) {
					reg.enrichIdentifier(MarketRegistration.COUPANG_VENDOR_ITEM_KEY, dto.getMarketProductCode());
					marketRegistrationRepository.save(reg);
				}
				log.info("[COUPANG] sellerProductId 역조회로 productId 매칭·vendorItemId 보강: "
					+ "sellerProductId={}, vendorItemId={}, sbProductId={}",
					dto.getSellerProductId(), dto.getMarketProductCode(), reg.getSbProductId());
				return reg.getSbProductId();
			}
		}

		log.warn("[COUPANG] sb_market_registration에서 productId를 찾을 수 없음: vendorItemId={}, sellerProductId={}",
			dto.getMarketProductCode(), dto.getSellerProductId());
		return null;
	}
}
