package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
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
		// F-SYNC-5: 기존/신규 판정·분기 골격만 공통 헬퍼에 위임. 갱신/생성의 스마트스토어 고유 로직
		// (findBySbCode 매핑, customsClearanceNo "undefined" 정규화, marketType 조건부 갱신 등)은 아래 콜백에 그대로 남는다.
		MarketOrderUpsertDispatcher.dispatch(
			marketOrders, orderRepository, "SMART_STORE", this::updateExistingOrder, this::createNewOrder);
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
		// D-120: 마켓 값이 실값일 때만 송장을 반영한다(빈 값/자리표시자로 기존 송장을 지우지 않음).
		boolean canOverwriteTracking = ShippingData.isMeaningfulTracking(dto.getTrackingNo());
		ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
			.trackingNo(canOverwriteTracking ? dto.getTrackingNo() : null)
			.shippingCarrier(canOverwriteTracking ? dto.getCarrier() : null)
			.shippingStatus(dto.getStatus())
			// D-129: 마켓이 준 송장을 채택했다면 마켓이 그 송장을 보유한다는 뜻이다.
			.trackingSentToMarket(ShippingData.marketOwnsTracking(dto.getTrackingNo()))
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
		log.info("[SMART_STORE] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());

		OrderLineItem lineItem = buildLineItemFromDto(dto, order.getId());
		orderLineItemRepository.save(lineItem);
	}

	private Order buildOrderFromDto(MarketOrderDto dto) {
		return Order.builder()
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
				// D-129: 마켓이 준 실송장이면 마켓 보유로 마킹(신규 주문 생성 경로).
				.trackingSentToMarket(ShippingData.marketOwnsTracking(dto.getTrackingNo()))
				.build())
			.settlementData(SettlementData.builder()
				.settlementAmount(marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.SMART_STORE))
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
