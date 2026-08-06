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
	/** 2단계: 배송 계층 upsert + 라인아이템 미러(설계 4.4). */
	private final OrderShipmentUpsertService orderShipmentUpsertService;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncElevenstOrders() {
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
				credential, LocalDate.now().minusDays(30), LocalDate.now());

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
		syncShipmentsAndLineItems(order, dto, lineItems);
	}

	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[ELEVEN_STREET] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());
		syncShipmentsAndLineItems(order, dto, List.of());
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
	private void syncShipmentsAndLineItems(Order order, MarketOrderDto dto, List<OrderLineItem> existing) {
		List<MarketShipmentDto> shipmentDtos = dto.getShipments();
		if (shipmentDtos == null || shipmentDtos.isEmpty()) {
			log.warn("[ELEVEN_STREET] 배송 계층이 없는 DTO — 건너뜀: orderNo={}", dto.getMarketOrderNo());
			return;
		}

		// 상품주문 → 소속 배송 역참조. 매칭 결과에서 배송을 되찾으려면 필요하다.
		java.util.IdentityHashMap<MarketLineItemDto, MarketShipmentDto> owner =
			new java.util.IdentityHashMap<>();
		List<OrderLineItemMatcher.Incoming> incoming = new java.util.ArrayList<>();
		for (MarketShipmentDto shipmentDto : shipmentDtos) {
			if (shipmentDto.getLineItems() == null) {
				continue;
			}
			for (MarketLineItemDto lineItemDto : shipmentDto.getLineItems()) {
				owner.put(lineItemDto, shipmentDto);
				incoming.add(new OrderLineItemMatcher.Incoming(lineItemDto, resolveProductId(lineItemDto)));
			}
		}

		OrderLineItemMatcher.MatchResult match =
			OrderLineItemMatcher.matchAndAdopt(existing, incoming);
		for (String warning : match.warnings()) {
			log.warn("[ELEVEN_STREET] orderNo={} {}", dto.getMarketOrderNo(), warning);
		}

		// 배송 upsert는 배송식별자당 한 번만. 여러 상품주문이 같은 배송을 가리킨다.
		java.util.IdentityHashMap<MarketShipmentDto, Shipment> shipments = new java.util.IdentityHashMap<>();
		for (MarketShipmentDto shipmentDto : shipmentDtos) {
			shipments.put(shipmentDto, orderShipmentUpsertService.upsertShipment(order.getId(), shipmentDto));
		}

		for (OrderLineItemMatcher.Adoption adoption : match.matched()) {
			OrderLineItem item = adoption.lineItem();
			updateLineItemFromDto(item, adoption.dto());
			orderLineItemRepository.save(item);
			orderShipmentUpsertService.linkToShipment(item, shipments.get(owner.get(adoption.dto())));
		}

		for (MarketLineItemDto lineItemDto : match.toCreate()) {
			OrderLineItem created = buildLineItemFromDto(lineItemDto, order.getId());
			orderLineItemRepository.save(created);
			orderShipmentUpsertService.linkToShipment(created, shipments.get(owner.get(lineItemDto)));
			log.info("[ELEVEN_STREET] 상품주문 신규 라인아이템 생성: orderNo={}, ordPrdSeq={}",
				dto.getMarketOrderNo(), lineItemDto.getMarketLineItemNo());
		}

		if (!match.unclaimed().isEmpty()) {
			if (!match.toCreate().isEmpty()) {
				// 이 조합이 사람의 확인을 요구한다: 새 행을 만들면서 옛 행을 짝짓지 못했다는 뜻이므로,
				// 소싱처·실구매가·구매상태가 붙은 행이 고아로 남았을 수 있다. 상품 정보가 양쪽에 없어
				// (예: 배송중 목록에만 있는 다품목 주문) 자동 판정이 불가능한 경우다.
				log.warn("[ELEVEN_STREET] ⚠ 확인 필요: orderNo={} 신규 {}건을 만들면서 기존 {}건을 짝짓지 못했다"
					+ " — 구매정보가 옛 행에 남아 있을 수 있다(라인아이템 id={})",
					dto.getMarketOrderNo(), match.toCreate().size(), match.unclaimed().size(),
					match.unclaimed().stream().map(OrderLineItem::getId).toList());
			} else {
				log.warn("[ELEVEN_STREET] orderNo={} 마켓이 더는 보내지 않는 라인아이템 {}건 — 지우지 않고 남긴다",
					dto.getMarketOrderNo(), match.unclaimed().size());
			}
		}
	}

	/**
	 * 상품주문 값을 라인아이템에 반영한다.
	 *
	 * <p>송장·택배사는 <b>건드리지 않는다</b> — 배송이 단일 원본이고 미러가 내려쓴다(D-133).
	 * 상태가 {@code UNKNOWN}이면 덮지 않는다. 새 상태명이 등장했을 때 배송중 주문이 신규로
	 * 되돌아가는 것이 가장 나쁜 실패다.
	 */
	private void updateLineItemFromDto(OrderLineItem item, MarketLineItemDto dto) {
		Long productId = resolveProductId(dto);
		if (productId != null && !productId.equals(item.getProductId())) {
			item.assignProductId(productId);
		}
		ShippingStatus status = dto.getStatus();
		if (status == null || status == ShippingStatus.UNKNOWN) {
			return;
		}
		ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
			.shippingStatus(status)
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
			.shipmentBoxId(dto.getShipmentBoxId())
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
	 * 상품주문 1건의 라인아이템을 만든다.
	 *
	 * <p>송장은 넣지 않는다 — 배송이 단일 원본이고 미러가 내려쓴다(D-133). 정산액은 이 상품주문의
	 * 금액으로 계산한다. 종전엔 주문 전체가 한 행이라 순번1 금액만 반영됐다.
	 */
	private OrderLineItem buildLineItemFromDto(MarketLineItemDto dto, Long orderId) {
		return OrderLineItem.builder()
			.orderId(orderId)
			.productId(resolveProductId(dto))
			.quantity(dto.getQuantity() != null ? dto.getQuantity() : 0)
			.marketLineItemNo(dto.getMarketLineItemNo())
			.shippingData(ShippingData.builder()
				.shippingStatus(dto.getStatus())
				.build())
			.settlementData(SettlementData.builder()
				.settlementAmount(marketFeeService.settlementAmount(
					dto.getTotalAmount(), MarketType.ELEVEN_STREET))
				.settlementVerified(false)
				.build())
			.build();
	}

	private Long resolveProductId(MarketLineItemDto dto) {
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

			// 사라진 주문의 실제 상태를 단건 상세조회로 판정. 클레임이 아니면 null → 상태 변경 없음(오취소 방지).
			ShippingStatus claimStatus = elevenstOrderAdapter.resolveClaimStatus(apiKey, order.getMarketOrderNo());
			if (claimStatus == null) {
				continue;
			}

			for (OrderLineItem item : items) {
				if (isNonTerminal(item)) {
					ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
						.shippingStatus(claimStatus)
						.build();
					item.applyShippingData(cmd.toShippingData(item.getShippingData()));
					orderLineItemRepository.save(item);
				}
			}
			claimCount++;
			log.info("[ELEVEN_STREET] 클레임 감지: ordNo={} → {}", order.getMarketOrderNo(), claimStatus);
		}

		if (claimCount > 0) {
			log.info("[ELEVEN_STREET] 클레임 감지: {}건 상태 반영", claimCount);
		}
	}

	/**
	 * terminal(종결) 상태가 아닌지 판정한다. fetchOrders가 조회하지 않는 종결 상태
	 * (CANCELED·DELIVERED·RETURNED·EXCHANGED)는 API 응답에 없어도 취소로 오인해선 안 된다. (D-028)
	 */
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
