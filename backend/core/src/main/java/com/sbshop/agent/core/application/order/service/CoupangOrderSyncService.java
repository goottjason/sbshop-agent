package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.fee.MarketFeeService;
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
	private final MarketFeeService marketFeeService;
	private final TerminalSettlementService terminalSettlementService;
	// D-087: 정산(S5)은 SyncCompletedEvent 미발행 → ActionLogSyncListener가 완료를 못 남긴다.
	// 이 서비스가 완료/실패를 COUPANG_SETTLEMENT_SYNC 로 직접 기록해 운영자가 실패를 인지하게 한다.
	private final com.sbshop.agent.core.application.actionlog.ActionLogService actionLogService;

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
			// 5. 사후 처리 (취소감지, 반품완료 반영, 택배사 보정)
			postSyncProcess(orders, credential);

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
		// F-SYNC-17: 교차 JVM(워커 스케줄러 + api 수동) 중복 실행 가드. DB 원자 클레임으로 스킵 판정.
		//   이미 RUNNING이면 tryMarkRunning이 false → 로그 후 return(스킵). in-JVM AtomicBoolean은 교차
		//   JVM을 못 막으므로 사용하지 않는다. 클레임 성공 시에만 진행하고, 해제는 markCompleted/markFailed.
		if (!syncStatusService.tryMarkRunning(
			com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG_SETTLEMENT)) {
			log.warn("[COUPANG] 정산 동기화 중복 실행 방지 — 이미 RUNNING 상태이므로 스킵");
			return;
		}
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
				// D-087: 정산 완료 기록(데이터 없음도 정상 완료).
				recordSettlement(com.sbshop.agent.core.domain.actionlog.enums.ActionStatus.SUCCESS,
					"쿠팡 정산 동기화 완료: 대상 없음");
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
			// D-087: 정산 완료 기록(실제 결과 반영).
			recordSettlement(com.sbshop.agent.core.domain.actionlog.enums.ActionStatus.SUCCESS,
				"쿠팡 정산 동기화 완료: " + updatedCount + "건 업데이트");
		} catch (Exception e) {
			log.error("쿠팡 정산 동기화 실패: {}", e.getMessage());
			syncStatusService.markFailed(
				com.sbshop.agent.core.application.sync.SyncMarketKeys.COUPANG_SETTLEMENT, e.getMessage());
			// D-087: 정산 실패 기록 — 이벤트 미발행 경로라 여기서만 남는다.
			recordSettlement(com.sbshop.agent.core.domain.actionlog.enums.ActionStatus.FAILED,
				"쿠팡 정산 동기화 실패: " + e.getMessage());
		}
	}

	/* ----- D-087: 정산 완료/실패 ActionLog 기록 헬퍼 ----- */
	private void recordSettlement(com.sbshop.agent.core.domain.actionlog.enums.ActionStatus status,
		String message) {
		actionLogService.record(
			com.sbshop.agent.core.domain.actionlog.ActionLogConstants.COUPANG_SETTLEMENT_SYNC,
			"COUPANG", status, message);
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
		// 2. D-120: 종전 가드는 "우리가 마켓에 보낸 송장(trackingSentToMarket=true)"일 때만 마켓 값을 반영했다.
		// 그러면 판매자가 쿠팡 윙에서 직접 입력한 송장은 영원히 유입되지 못한다 — 마켓이 진실 원본이라는
		// 원칙에 어긋난다. 가드의 실제 목적은 "마켓의 빈 값이 우리 실값을 지우는 것"을 막는 것이므로,
		// 전송 여부가 아니라 마켓 값의 유의미성으로 판정한다(D-107/108 PII 가드와 동형).
		boolean canOverwriteTracking = ShippingData.isMeaningfulTracking(dto.getTrackingNo());
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
		// 2. 정산액 초기값 = 총액 × (1 - 쿠팡 수수료율/100). 마켓별 요율(FeePolicy)을 sync에서 1회만 적용.
		//    (실제 정산 API 값이 오면 위 콜백에서 applySettlement로 덮어씀.)
		BigDecimal settlementAmount = marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.COUPANG);

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
	private void postSyncProcess(List<MarketOrderDto> orders, MarketCredential credential) {
		LocalDate fromDate = LocalDate.now().minusDays(30);
		LocalDate toDate = LocalDate.now();
		// 1. API에 없는 주문 → CANCELED 처리
		coupangOrderAdapter.detectCancellations(orders, fromDate, toDate);
		// 2. (D-097) 반품완료 전방 감지 → DELIVERED건을 RETURNED+정산0으로 전환
		coupangOrderAdapter.detectReturns(credential, fromDate, toDate);
		// 3. (D-098) 취소 종결 lineItem 정산0 정규화(반품은 detectReturns가 이미 처리, 여기선 CANCELED 커버·멱등).
		terminalSettlementService.zeroSettlementForRefunded(MarketType.COUPANG);
		// 4. 택배사 ETC → 실제 택배사로 보정
		coupangOrderAdapter.fixCarriers(orders);
	}
}
