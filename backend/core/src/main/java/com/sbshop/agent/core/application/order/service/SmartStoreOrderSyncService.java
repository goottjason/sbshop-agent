package com.sbshop.agent.core.application.order.service;

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
import java.time.LocalDate;
import java.util.List;
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
	private final com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;
	private final MarketFeeService marketFeeService;
	private final TerminalSettlementService terminalSettlementService;
	/** 5단계: 3계층 반영 공통 골격. 마켓별 차이는 {@code syncPolicy}가 흡수한다(D-138). */
	private final MarketLineItemSyncDispatcher lineItemSyncDispatcher;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncSmartStoreOrders() {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[SMART_STORE] 동기화 중복 실행 방지");
			return;
		}

		// F-SYNC-2: 상태 기록을 async 스레드(이 본문) 안에서 수행.
		syncStatusService.markRunning(com.sbshop.agent.core.application.sync.SyncMarketKeys.SMART_STORE);
		boolean success = false;
		try {
			MarketCredential credential = loadAndValidateCredential();
			List<MarketOrderDto> orders = smartStoreOrderAdapter.fetchOrders(
				credential, LocalDate.now().minusDays(30), LocalDate.now());

			processOrders(orders, credential);
			postSyncProcess(orders);

			log.info("[SMART_STORE] 주문 동기화 완료: {}건 처리", orders.size());
			success = true;
			syncStatusService.markCompleted(com.sbshop.agent.core.application.sync.SyncMarketKeys.SMART_STORE);
		} catch (Exception e) {
			log.error("[SMART_STORE] 주문 동기화 실패: {}", e.getMessage(), e);
			syncStatusService.markFailed(
				com.sbshop.agent.core.application.sync.SyncMarketKeys.SMART_STORE, e.getMessage());
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

		// D-043: 빈 문자열/공백도 불완전으로 fast-fail (access/secret EMPTY를 API 이전에 명확히 실패).
		if (!StringUtils.hasText(credential.getClientId())
			|| !StringUtils.hasText(credential.getSecretKey())) {
			throw new IllegalArgumentException("스마트스토어 크레덴셜 불완전: client-id/secret-key 확인");
		}
		return credential;
	}

	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential) {
		// 5단계: 어댑터가 3계층으로 내주지만, 정규화기를 경계에 둬서 평면 DTO가 들어와도
		// 배송 1 : 상품주문 1로 감싸진다(설계 5.1).
		marketOrders = marketOrders.stream().map(MarketOrderNormalizer::normalize).toList();
		// 기존/신규 판정을 공통 헬퍼(F-SYNC-5)에 맡기지 않는다 — 헬퍼의 계약은 "marketOrderNo로 찾는다"
		// 인데, 5단계부터 N스토어는 <b>새 키로 못 찾으면 옛 키로도 찾는</b> 마켓 고유 규칙을 갖는다.
		for (MarketOrderDto dto : marketOrders) {
			log.info("[SMART_STORE] 처리 중: orderNo={}, status={}", dto.getMarketOrderNo(), dto.getStatus());
			Order existing = findExistingOrder(dto);
			if (existing != null) {
				log.info("[SMART_STORE] 기존 주문 발견: id={}, orderNo={}", existing.getId(), dto.getMarketOrderNo());
				updateExistingOrder(existing, dto);
			} else {
				log.info("[SMART_STORE] 신규 주문 생성 시도: orderNo={}", dto.getMarketOrderNo());
				createNewOrder(dto);
			}
		}
	}

	/**
	 * 이 주문의 기존 행을 찾는다. 없으면 <b>옛 키(상품주문번호)로도 찾아 키를 갈아 끼운다</b> —
	 * <b>이전(migration)이 곧 동기화다.</b>
	 *
	 * <p>5단계 전에는 상품주문번호를 주문번호로 썼다(설계 §9.2). 새 키로 못 찾는다고 신규 생성하면
	 * 같은 주문이 두 행이 되고, 소싱처·실구매가·구매상태가 붙은 옛 행이 고아가 된다. 배포 시점과
	 * 데이터 이전 시점이 어긋나도 안전하도록 동기화 스스로 이전한다.
	 *
	 * <p>새 키로 이미 있으면 옛 키를 조회조차 하지 않는다 — {@code market_order_no}는 전역
	 * 유니크라 그 상태에서 키를 갈면 제약 위반으로 동기화가 통째로 실패한다.
	 */
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
			// 한 주문이 옛 키로 여러 행에 흩어져 있었다는 뜻이다(상품주문마다 한 행). 병합은 사람이
			// 판단해야 한다 — 어느 행의 소싱·구매정보를 살릴지 자동으로 정할 수 없다.
			log.warn("[SMART_STORE] ⚠ 확인 필요: 주문 {}의 옛 행이 더 있다 — id={}, 옛 키={}."
				+ " 라인아이템을 id={}로 옮기고 이 행을 정리해야 한다",
				newKey, legacy.getId(), legacyKey, rekeyed.getId());
		}
		return rekeyed;
	}

	/** 이 주문의 상품주문번호들 — 전환 전에는 이 값 하나하나가 주문번호였다. */
	private List<String> legacyOrderKeys(MarketOrderDto dto) {
		if (dto.getShipments() == null) {
			return List.of();
		}
		return dto.getShipments().stream()
			.filter(shipment -> shipment.getLineItems() != null)
			.flatMap(shipment -> shipment.getLineItems().stream())
			.map(com.sbshop.agent.core.application.order.dto.MarketLineItemDto::getMarketLineItemNo)
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
		applyMarketSpecificData(order, dto);
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
			.shipmentBoxId(dto.getShipmentBoxId())
			.build();
		applyMarketSpecificData(order, dto);
		return order;
	}

	/** 주문 계층 마켓 데이터(productOrderIds)를 반영한다 — 발주확인·주문취소가 읽는다. */
	private void applyMarketSpecificData(Order order, MarketOrderDto dto) {
		if (dto.getMarketSpecificData() == null || dto.getMarketSpecificData().isEmpty()) {
			return;
		}
		java.util.Map<String, String> stringMap = new java.util.HashMap<>();
		for (java.util.Map.Entry<String, Object> entry : dto.getMarketSpecificData().entrySet()) {
			stringMap.put(entry.getKey(), String.valueOf(entry.getValue()));
		}
		order.setMarketSpecificDataFromMap(stringMap);
	}

	/**
	 * 이 마켓의 3계층 동기화 정책. 골격이 갖지 않는 세 가지만 구현한다 —
	 * 로그 태그·상품 해석·라인아이템 생성(정산액 산출).
	 */
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
	};

	/**
	 * 상품주문 1건의 라인아이템을 만든다.
	 *
	 * <p>송장은 넣지 않는다 — 배송이 단일 원본이고 미러가 내려쓴다(D-133).
	 */
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

	/**
	 * 정산액은 <b>마켓이 알려준 실측값</b>({@code expectedSettlementAmount})을 쓰고, 없을 때만 요율로
	 * 추정한다. 추정은 D-122(스마트스토어 수수료율 가정 8% vs 실제 4.9%)에서 드러난 것처럼 괴리를
	 * 낳는다. 상품주문별로 실측값이 오므로 다품목 주문의 분배 문제도 함께 사라진다(설계 §9.1).
	 */
	private java.math.BigDecimal resolveSettlementAmount(MarketLineItemDto dto) {
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
		// D-098: 취소·반품 종결 lineItem 정산0 정규화(멱등). 네이버는 취소/반품을 갱신상태로 계속
		// 반환하므로 상태 감지는 갱신 경로에서 이뤄지고, 여기선 그 종결 건의 정산액을 0으로 내린다.
		terminalSettlementService.zeroSettlementForRefunded(MarketType.SMART_STORE);
	}
}
