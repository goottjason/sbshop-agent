package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
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
	private final com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;
	private final MarketFeeService marketFeeService;
	private final TerminalSettlementService terminalSettlementService;
	/** 3계층 반영 공통 골격. 마켓별 차이는 {@code syncPolicy}가 흡수한다. */
	private final MarketLineItemSyncDispatcher lineItemSyncDispatcher;
	/** D-158: 사라진 주문의 마켓 보유 송장을 배송 계층에 기록하기 위해 필요하다. */
	private final com.sbshop.agent.core.domain.order.repository.ShipmentRepository shipmentRepository;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncElevenstOrders() {
		syncElevenstOrders(30);
	}

	/**
	 * 조회 기간(일)을 지정한 동기화. 기본 경로는 30일이고, <b>과거 구간 백필</b>에만 넓게 쓴다.
	 *
	 * <p>배경(2026-08-08): `market_tracking_no`(마켓 보유 송장)는 D-148에서 신설됐다. 그전에 30일 창을
	 * 벗어난 주문들은 이 값을 가질 기회가 없었고, 그래서 화면이 반영 여부를 판정하지 못했다
	 * (창 안 주문은 전 마켓 100% 수집되고 있었다 — 진행 중 동작은 이미 일관됐다).
	 * 새 경로를 만들지 않고 <b>검증된 동기화 경로를 넓은 기간으로 한 번 더 돌리는</b> 방식을 쓴다.
	 */
	public void syncElevenstOrders(int lookbackDays) {
		syncElevenstOrders(LocalDate.now().minusDays(lookbackDays), LocalDate.now());
	}

	/** 조회 구간을 직접 지정한 동기화. 백필이 마켓 API 제약(범위 상한·레이트리밋)에 맞춰
	 *  구간을 나눠 걸을 때 쓴다. */
	@Transactional
	public void syncElevenstOrders(LocalDate fromDate, LocalDate toDate) {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[ELEVEN_STREET] 동기화 중복 실행 방지");
			return;
		}

		// F-SYNC-2: 상태 기록을 async 스레드(이 본문) 안에서 수행.
		syncStatusService.markRunning(com.sbshop.agent.core.application.sync.SyncMarketKeys.ELEVEN_STREET);
		boolean success = false;
		try {
			MarketCredential credential = loadAndValidateCredential();
			List<MarketOrderDto> orders = elevenstOrderAdapter.fetchOrders(
				credential, fromDate, toDate);

			processOrders(orders, credential);
			postSyncProcess(orders, credential);

			log.info("[ELEVEN_STREET] 주문 동기화 완료: {}건 처리", orders.size());
			success = true;
			syncStatusService.markCompleted(com.sbshop.agent.core.application.sync.SyncMarketKeys.ELEVEN_STREET);
		} catch (Exception e) {
			log.error("[ELEVEN_STREET] 주문 동기화 실패: {}", e.getMessage(), e);
			syncStatusService.markFailed(
				com.sbshop.agent.core.application.sync.SyncMarketKeys.ELEVEN_STREET, e.getMessage());
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, MarketType.ELEVEN_STREET, false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			if (success) {
				eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.ELEVEN_STREET));
			}
		}
	}

	private MarketCredential loadAndValidateCredential() {
		MarketCredential credential = credentialRepository.findByMarketType(MarketType.ELEVEN_STREET)
			.orElseThrow(() -> new IllegalArgumentException("ELEVEN_STREET 크레덴셜 없음"));

		// D-043: 빈 문자열/공백도 불완전으로 fast-fail (secret-key EMPTY 등을 API 이전에 명확히 실패).
		if (!StringUtils.hasText(credential.getAccessKey())) {
			throw new IllegalArgumentException("11번가 크레덴셜 불완전: API Key(access-key) 확인");
		}
		return credential;
	}

	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential) {
		// 2단계: 어댑터가 3계층으로 내주지만, 정규화기를 경계에 둬서 평면 DTO가 들어와도
		// 배송 1 : 상품주문 1로 감싸진다(설계 5.1). 이 서비스가 정규화기의 첫 소비자다.
		marketOrders = marketOrders.stream().map(MarketOrderNormalizer::normalize).toList();
		// F-SYNC-5: 기존/신규 판정·분기 골격만 공통 헬퍼에 위임. 갱신/생성의 11번가 고유 로직
		// (findBySbCode 매핑, marketSpecificData 반영, marketType 조건부 갱신 등)은 아래 콜백에 그대로 남는다.
		// 취소 감지(detectCancellations)는 postSyncProcess 경로에 그대로 유지된다.
		MarketOrderUpsertDispatcher.dispatch(
			marketOrders, orderRepository, "ELEVEN_STREET", this::updateExistingOrder, this::createNewOrder);
	}

	private void updateExistingOrder(Order order, MarketOrderDto dto) {
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
		updateOrderInfoFromDto(order, dto, lineItems);
		orderRepository.save(order);
		lineItemSyncDispatcher.sync(order, dto, lineItems, syncPolicy);
	}

	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[ELEVEN_STREET] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());
		lineItemSyncDispatcher.sync(order, dto, List.of(), syncPolicy);
	}

	/**
	 * 2단계 핵심: 배송을 upsert하고 상품주문마다 라인아이템을 맞춰 넣는다.
	 *
	 * <p>매칭은 <b>주문 전체에서 한 번</b> 한다. 배송별로 나눠 매칭하면, 상품주문이 다른 배송으로
	 * 옮겨갔을 때(묶음 → 개별 분리) 같은 기존 행을 두 배송이 각각 채택해 중복이 생긴다.
	 *
	 * <p>송장은 여기서 직접 쓰지 않는다 — 배송이 단일 원본이고 {@code linkToShipment}가 내려쓴다
	 * (설계 4.4, D-133). 진행상태·상품·정산액만 라인아이템에 반영한다.
	 */
	private void updateOrderInfoFromDto(Order order, MarketOrderDto dto, List<OrderLineItem> lineItems) {
		// D-074: 진행(PREPARING 이상) lineItem 존재 시 주소·우편번호를 API 값으로 덮지 않음(수기 보정 보호, 세트).
		boolean protectAddress = lineItems.stream().anyMatch(OrderLineItem::isProgressed);
		order.update(
			dto.getRecipientName(), dto.getRecipientPhone(),
			protectAddress ? null : dto.getZipcode(), protectAddress ? null : dto.getAddress(), dto.getMessage(),
			dto.getOrdererName(), dto.getOrdererPhone(),
			dto.getMarketType() != null && dto.getMarketType() != order.getMarketType() ? dto.getMarketType() : null);
		// 통관번호는 실값일 때만 반영한다 — 마켓은 배송중·배송완료에서 이 필드를 빼거나 마스킹해 주고,
		// 한 번 지워지면 마켓에서 되받을 수 없어 복구가 불가능하다(도메인 가드가 정본).
		order.applyCustomsClearanceNoFromMarket(dto.getCustomsClearanceNo());
		if (dto.getMarketSpecificData() != null && !dto.getMarketSpecificData().isEmpty()) {
			java.util.Map<String, String> stringMap = new java.util.HashMap<>();
			for (java.util.Map.Entry<String, Object> entry : dto.getMarketSpecificData().entrySet()) {
				stringMap.put(entry.getKey(), String.valueOf(entry.getValue()));
			}
			order.setMarketSpecificDataFromMap(stringMap);
		}
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
			java.util.Map<String, String> stringMap = new java.util.HashMap<>();
			for (java.util.Map.Entry<String, Object> entry : dto.getMarketSpecificData().entrySet()) {
				stringMap.put(entry.getKey(), String.valueOf(entry.getValue()));
			}
			order.setMarketSpecificDataFromMap(stringMap);
		}

		return order;
	}

	/**
	 * 이 마켓의 3계층 동기화 정책. 골격이 갖지 않는 세 가지만 구현한다 —
	 * 로그 태그·상품 해석·라인아이템 생성(정산액 산출).
	 */
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
	};

	/**
	 * 상품주문 1건의 라인아이템을 만든다.
	 *
	 * <p>송장은 넣지 않는다 — 배송이 단일 원본이고 미러가 내려쓴다(D-133). 정산액은 이 상품주문의
	 * 금액으로 계산한다. 종전엔 주문 전체가 한 행이라 순번1 금액만 반영됐다.
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
	 * 정산액은 <b>마켓이 알려준 실측값</b>({@code stlPlnAmt})을 쓰고, 없을 때만 요율로 추정한다.
	 *
	 * <p>추정은 D-122(스마트스토어 수수료율 가정 8% vs 실제 4.9%)에서 드러난 것처럼 괴리를 낳는다.
	 * 상품주문별로 실측값이 오므로 다품목 주문의 분배 문제도 함께 사라진다(설계 9.1).
	 */
	private java.math.BigDecimal resolveSettlementAmount(MarketLineItemDto dto) {
		if (dto.getSettlementAmount() != null && dto.getSettlementAmount().signum() != 0) {
			return dto.getSettlementAmount();
		}
		return marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.ELEVEN_STREET);
	}

	private Long elevenstResolveProductId(MarketLineItemDto dto) {
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

	/**
	 * 사후 처리: API 응답에 없는 기존 주문 중 non-terminal 상태를 CANCELED로 감지한다.
	 * 쿠팡 detectCancellations 정본 패턴을 이식 — 11번가는 취소/반품/교환 조회 API가 없어
	 * 이 감지가 없으면 취소된 주문이 이전 상태(NEW/PREPARING)로 영구 잔류한다. (D-028)
	 */
	private void postSyncProcess(List<MarketOrderDto> orders, MarketCredential credential) {
		LocalDate fromDate = LocalDate.now().minusDays(30);
		LocalDate toDate = LocalDate.now();
		detectClaims(orders, fromDate, toDate, credential.getAccessKey());
		// D-098: 취소·반품 종결 lineItem 정산0 정규화(멱등).
		terminalSettlementService.zeroSettlementForRefunded(MarketType.ELEVEN_STREET);
	}

	/**
	 * D-099: 진행상태 목록에서 사라진 주문의 실제 상태를 단건 상세조회로 판정해 취소·반품·교환을 구분 반영한다.
	 *
	 * <p>기존(D-028)엔 사라진 주문을 무조건 CANCELED로 뭉뚱그렸다. 11번가는 클레임 목록 조회 REST가 없어
	 * (라이브 확정) 이 방법뿐이었으나, 상세조회의 ordPrdStatNm으로 취소/반품/교환을 구분할 수 있어 정밀화한다.
	 * 상세조회가 클레임이 아니라고 답하면(구매확정 등 정상 상태로 목록 창을 벗어난 경우) 상태를 바꾸지 않아
	 * 오취소를 막는다. 반품·취소는 이어지는 정산0 정규화(D-098)로 정산액도 0이 된다.
	 */
	private void detectClaims(List<MarketOrderDto> apiOrders, LocalDate fromDate, LocalDate toDate, String apiKey) {
		java.util.Set<String> apiOrderNos = new java.util.HashSet<>();
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
			if (items.stream().noneMatch(this::isNonTerminal)) {
				continue;
			}

			// 사라진 주문의 실제 상태를 단건 상세조회로 판정. 종결 상태가 아니면 빈 결과 → 상태 변경 없음(오취소 방지).
			// 2단계 정정: 응답이 상품주문마다 한 행이므로 순번별로 적용한다. 종전에는 첫 행의 상태를
			// 주문 전체에 씌워, 한 상품만 취소된 주문의 나머지 상품까지 취소로 만들 수 있었다.
			// D-157/D-158: 클레임만 보던 것을 "종결 상태 + 마켓 보유 송장"으로 넓혔다.
			ElevenstOrderAdapter.MissingOrderState state =
				elevenstOrderAdapter.resolveMissingOrderState(apiKey, order.getMarketOrderNo());
			if (state.isEmpty()) {
				continue;
			}
			applyMarketTrackingFromMissingOrder(order, state);
			java.util.Map<String, ShippingStatus> claims = state.statuses();
			if (claims.isEmpty()) {
				continue;
			}

			int applied = 0;
			for (OrderLineItem item : items) {
				if (!isNonTerminal(item)) {
					continue;
				}
				ShippingStatus claimStatus = resolveClaimFor(item, claims);
				if (claimStatus == null) {
					continue;
				}
				ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
					.shippingStatus(claimStatus)
					.build();
				item.applyShippingData(cmd.toShippingData(item.getShippingData()));
				orderLineItemRepository.save(item);
				applied++;
			}
			if (applied == 0) {
				continue;
			}
			claimCount++;
			log.info("[ELEVEN_STREET] 클레임 감지: ordNo={} → {}건 반영 {}",
				order.getMarketOrderNo(), applied, claims);
		}

		if (claimCount > 0) {
			log.info("[ELEVEN_STREET] 클레임 감지: {}건 상태 반영", claimCount);
		}
	}

	/**
	 * terminal(종결) 상태가 아닌지 판정한다. fetchOrders가 조회하지 않는 종결 상태
	 * (CANCELED·DELIVERED·RETURNED·EXCHANGED)는 API 응답에 없어도 취소로 오인해선 안 된다. (D-028)
	 */
	/**
	 * 이 라인아이템에 적용할 클레임 상태를 고른다.
	 *
	 * <p>상품주문번호가 있으면 그 순번의 클레임만 적용한다 — 한 상품만 취소된 주문의 나머지 상품을
	 * 함께 취소하지 않는다. 순번이 없는 레거시 행은 주문 전체 클레임(순번 미상)이나, 클레임이
	 * 하나뿐일 때 그것을 적용한다(종전 동작 보존 — 그때는 라인아이템도 하나였다).
	 */
	/**
	 * D-158: 사라진 주문의 응답에 담긴 <b>마켓 보유 송장</b>을 배송 계층에 기록한다.
	 *
	 * <p>우리 송장은 덮지 않는다 — 마켓 값은 "마켓이 아는 값"으로만 보관한다(D-148 규율).
	 * 이 값이 있어야 화면이 두 송장을 비교해 반영 여부를 정직하게 표시할 수 있고(D-149),
	 * 값이 우리 송장과 같아지면 수동수정 표시가 스스로 꺼진다(사람이 판매자센터에서 고친 경우).
	 */
	private void applyMarketTrackingFromMissingOrder(Order order,
		ElevenstOrderAdapter.MissingOrderState state) {
		if (state.trackingNos().isEmpty()) {
			return;
		}
		// 순번별 값이 여럿이면 주문 단위 배송에 붙일 근거가 없다 — 값이 하나로 모일 때만 기록한다.
		java.util.Set<String> distinct = new java.util.HashSet<>(state.trackingNos().values());
		if (distinct.size() != 1) {
			return;
		}
		String marketTracking = distinct.iterator().next();
		for (Shipment shipment : shipmentRepository.findByOrderId(order.getId())) {
			shipment.applyMarketTracking(marketTracking);
			shipmentRepository.save(shipment);
		}
	}

	private ShippingStatus resolveClaimFor(OrderLineItem item, java.util.Map<String, ShippingStatus> claims) {
		String seq = item.getMarketLineItemNo();
		if (seq != null) {
			return claims.get(seq);
		}
		ShippingStatus orderWide = claims.get(ElevenstOrderAdapter.CLAIM_ORDER_WIDE);
		if (orderWide != null) {
			return orderWide;
		}
		return claims.size() == 1 ? claims.values().iterator().next() : null;
	}

	private boolean isNonTerminal(OrderLineItem item) {
		if (item.getShippingData() == null) {
			return true;
		}
		ShippingStatus s = item.getShippingData().getShippingStatus();
		return s != ShippingStatus.CANCELED
			&& s != ShippingStatus.DELIVERED
			&& s != ShippingStatus.RETURNED
			&& s != ShippingStatus.EXCHANGED;
	}
}
