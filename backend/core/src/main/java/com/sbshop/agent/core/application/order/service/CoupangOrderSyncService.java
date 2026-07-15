package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
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
import java.util.Map;
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

	// -- DI --
	private final MarketCredentialRepository credentialRepository;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final ProductRepository productRepository;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final CoupangOrderAdapter coupangOrderAdapter;
	private final CoupangStatusMapper statusMapper;
	private final com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;

	// -- 상태 --
	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	/* ----- 진입점 : 주문 동기화 ----- */
	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangOrders() {
		// 1. 중복 실행 방지
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[COUPANG] 동기화 중복 실행 방지");
			return;
		}

		// F-SYNC-2: 상태 기록을 async 스레드(이 본문) 안에서 수행.
		syncStatusService.markRunning(com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG);
		boolean success = false;
		try {
			// 2. 크레덴셜 로드
			MarketCredential credential = loadAndValidateCredential();
			// 3. API 호출 → 주문 목록 획득 (최근 30일)
			List<MarketOrderDto> orders = coupangOrderAdapter.fetchOrders(
				credential, LocalDate.now().minusDays(30), LocalDate.now());
			// 4. 주문 저장/업데이트
			processOrders(orders, credential);
			// 5. 사후 처리 (취소감지, 택배사 보정)
			postSyncProcess(orders);

			log.info("[COUPANG] 주문 동기화 완료: {}건 처리", orders.size());
			success = true;
			syncStatusService.markCompleted(com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG);
		} catch (Exception e) {
			log.error("[COUPANG] 주문 동기화 실패: {}", e.getMessage(), e);
			syncStatusService.markFailed(
				com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG, e.getMessage());
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, MarketType.COUPANG, false, e.getMessage()));
		} finally {
			// 6. 락 해제 + SSE 알림 (성공 시에만 SYNC_COMPLETED 발행 — 실패 시 catch의 SYNC_FAILED 유지)
			isSyncing.set(false);
			if (success) {
				eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.COUPANG));
			}
		}
	}

	/* ----- 진입점 : 정산 동기화 ----- */
	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangSettlement() {
		// F-SYNC-2: 정산 동기화도 자기 상태를 async 스레드 안에서 기록.
		syncStatusService.markRunning(com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG_SETTLEMENT);
		try {
			// 1. 크레덴셜 로드
			MarketCredential credential = loadAndValidateCredential();

			LocalDate fromDate = LocalDate.now().minusDays(31);
			LocalDate toDate = LocalDate.now().minusDays(1);

			log.info("쿠팡 정산 동기화 시작: {} ~ {}", fromDate, toDate);

			// 2. 정산 API 호출 (sbCode → settlementAmount 맵)
			Map<String, BigDecimal> settlementMap = coupangOrderAdapter.querySettlement(
				credential, fromDate, toDate);

			if (settlementMap.isEmpty()) {
				log.info("쿠팡 정산 데이터 없음");
				syncStatusService.markCompleted(
					com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG_SETTLEMENT);
				return;
			}

			// 3. 배송완료 주문 순회 → 정산액 업데이트
			List<Order> coupangOrders = orderRepository.findByMarketType(MarketType.COUPANG);
			int updatedCount = 0;

			for (Order order : coupangOrders) {
				List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
				for (OrderLineItem item : lineItems) {
					// 3a. 배송완료 건만 대상
					if (item.getShippingData() == null
						|| item.getShippingData()
							.getShippingStatus() != com.sbshop.agent.core.domain.order.enums.ShippingStatus.DELIVERED) {
						continue;
					}
					// 3b. 상품코드 조회
					if (item.getProductId() == null)
						continue;
					String sbCode = productRepository.findById(item.getProductId())
						.map(Product::getSbCode).orElse(null);
					if (sbCode == null || sbCode.isEmpty())
						continue;
					// 3c. 정산금액 갱신 (변경 시만)
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
				com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG_SETTLEMENT);
		} catch (Exception e) {
			log.error("쿠팡 정산 동기화 실패: {}", e.getMessage());
			syncStatusService.markFailed(
				com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG_SETTLEMENT, e.getMessage());
		}
	}

	/* ----- 크레덴셜 조회 및 검증 ----- */
	private MarketCredential loadAndValidateCredential() {
		// 1. DB 조회
		MarketCredential credential = credentialRepository.findByMarketType(MarketType.COUPANG)
			.orElseThrow(() -> new IllegalArgumentException("COUPANG 크레덴셜 없음"));
		// 2. 필수값 검증
		if (credential.getClientId() == null || credential.getAccessKey() == null
			|| credential.getSecretKey() == null) {
			throw new IllegalArgumentException("쿠팡 크레덴셜 불완전");
		}
		return credential;
	}

	/* ----- 주문 목록 저장/업데이트 ----- */
	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential) {
		// F-SYNC-5: 기존/신규 판정·분기 골격만 공통 헬퍼에 위임. 갱신/생성의 쿠팡 고유 로직
		// (trackingSentToMarket 보존 가드, 정산액 ×0.89, sellerProductId 역조회 보강 등)은 아래 콜백에 그대로 남는다.
		MarketOrderUpsertDispatcher.dispatch(
			marketOrders, orderRepository, "COUPANG", this::updateExistingOrder, this::createNewOrder);
	}

	/* ----- 기존 주문 업데이트 ----- */
	private void updateExistingOrder(Order order, MarketOrderDto dto) {
		// 1. lineItem 조회
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
		// 2. lineItem별 업데이트 (productId, trackingNo, carrier, status)
		for (OrderLineItem item : lineItems) {
			updateLineItemFromDto(item, dto);
		}
		// 3. 주문 정보 업데이트 (받는이, 주소, 메시지 등)
		updateOrderInfoFromDto(order, dto, lineItems);
		// 4. 저장
		orderRepository.save(order);
		lineItems.forEach(orderLineItemRepository::save);
	}

	/* ----- LineItem 업데이트 ----- */
	private void updateLineItemFromDto(OrderLineItem item, MarketOrderDto dto) {
		// 1. productId 매칭
		Long productId = resolveProductId(dto);
		if (productId != null && !productId.equals(item.getProductId())) {
			item.assignProductId(productId);
		}
		// 2. trackingSentToMarket 가드: false/null이면 trackingNo/carrier 보존
		boolean canOverwriteTracking = item.getShippingData() != null
			&& Boolean.TRUE.equals(item.getShippingData().getTrackingSentToMarket());
		// 3. 배송 정보 갱신 (shippingStatus는 항상 갱신, trackingNo/carrier는 조건부)
		ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
			.trackingNo(canOverwriteTracking ? dto.getTrackingNo() : null)
			.shippingCarrier(canOverwriteTracking ? dto.getCarrier() : null)
			.shippingStatus(dto.getStatus())
			.build();
		item.applyShippingData(cmd.toShippingData(item.getShippingData()));
	}

	/* ----- 주문 정보 업데이트 ----- */
	private void updateOrderInfoFromDto(Order order, MarketOrderDto dto, List<OrderLineItem> lineItems) {
		// PREPARING 이상 lineItem 존재 시 address·zipcode 보호 (API 값으로 덮지 않음, 세트 — D-074)
		boolean protectAddress = lineItems.stream().anyMatch(OrderLineItem::isProgressed);
		order.update(
			dto.getRecipientName(),
			dto.getRecipientPhone(),
			protectAddress ? null : dto.getZipcode(),
			protectAddress ? null : dto.getAddress(),
			dto.getMessage(),
			dto.getOrdererName(),
			dto.getOrdererPhone(),
			dto.getShipmentBoxId(),
			dto.getMarketType());
		// 4. 통관번호 갱신
		if (dto.getCustomsClearanceNo() != null) {
			order.updateCustomsClearanceNo(dto.getCustomsClearanceNo());
		}
	}

	/* ----- 신규 주문 생성 ----- */
	private void createNewOrder(MarketOrderDto dto) {
		// 1. Order 엔티티 생성 및 저장
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[COUPANG] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());
		// 2. OrderLineItem 생성 및 저장
		OrderLineItem lineItem = buildLineItemFromDto(dto, order.getId());
		orderLineItemRepository.save(lineItem);
	}

	/* ----- DTO → Order 변환 ----- */
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

	/* ----- DTO → OrderLineItem 변환 ----- */
	private OrderLineItem buildLineItemFromDto(MarketOrderDto dto, Long orderId) {
		// 1. productId 매칭 (market_registration)
		Long productId = resolveProductId(dto);
		// 2. 정산액 초기값 (totalAmount × 0.89 = 수수료 11% 차감)
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

	/* ----- MarketRegistration → sb_productId 조회 ----- */
	private Long resolveProductId(MarketOrderDto dto) {
		if (dto.getMarketProductCode() != null) {
			// 1. vendorItemId로 market_registration 검색 (2회차 이후 동기화는 여기서 직접 매칭)
			List<MarketRegistration> regs = marketRegistrationRepository
				.findByMarketTypeAndIdentifiersContaining(MarketType.COUPANG, dto.getMarketProductCode());
			if (!regs.isEmpty()) {
				Long sbProductId = regs.get(0).getSbProductId();
				log.info("[COUPANG] sb_market_registration에서 productId 조회: vendorItemId={}, sbProductId={}",
					dto.getMarketProductCode(), sbProductId);
				return sbProductId;
			}
		}

		// 2. (D-046) vendorItemId 미스매치 시 sellerProductId로 역조회 후 vendorItemId 보강.
		//    발행 시 marketIdentifiers에 sellerProductId만 저장되고 vendorItemId를 채우는 write-path가
		//    없어 매핑이 끊기던 근본원인을, 최초 주문 동기화 시점에 보강해 잇는다.
		if (dto.getSellerProductId() != null && !dto.getSellerProductId().isEmpty()) {
			List<MarketRegistration> regsBySeller = marketRegistrationRepository
				.findByMarketTypeAndIdentifiersContaining(MarketType.COUPANG, dto.getSellerProductId());
			if (!regsBySeller.isEmpty()) {
				MarketRegistration reg = regsBySeller.get(0);
				// vendorItemId를 marketIdentifiers에 보강 → 이후 동기화부터 1의 직접 매칭이 성립
				if (dto.getMarketProductCode() != null && !dto.getMarketProductCode().isEmpty()) {
					reg.enrichIdentifier("vendorItemId", dto.getMarketProductCode());
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

	/* ----- 통관정보 생성 ----- */
	private CustomsData buildCustomsData(MarketOrderDto dto) {
		return CustomsData.builder()
			.customsClearanceNo(dto.getCustomsClearanceNo())
			.build();
	}

	/* ----- 사후 처리 ----- */
	private void postSyncProcess(List<MarketOrderDto> orders) {
		LocalDate fromDate = LocalDate.now().minusDays(30);
		LocalDate toDate = LocalDate.now();
		// 1. API에 없는 주문 → CANCELED 처리
		coupangOrderAdapter.detectCancellations(orders, fromDate, toDate);
		// 2. 택배사 ETC → 실제 택배사로 보정
		coupangOrderAdapter.fixCarriers(orders);
	}
}
