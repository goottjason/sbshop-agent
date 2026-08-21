package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
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

	/** 3계층 반영 공통 골격. 마켓별 차이는 {@code syncPolicy}가 흡수한다. */
	private final MarketLineItemSyncDispatcher lineItemSyncDispatcher;

	// -- 상태 --
	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	/* ----- 진입점 : 주문 동기화 ----- */
	@Async("syncTaskExecutor")
	@Transactional
	public void syncCoupangOrders() {
		syncCoupangOrders(30);
	}

	/**
	 * 조회 기간(일)을 지정한 동기화. 기본 경로는 30일이고, <b>과거 구간 백필</b>에만 넓게 쓴다.
	 *
	 * <p>배경(2026-08-08): `market_tracking_no`(마켓 보유 송장)는 D-148에서 신설됐다. 그전에 30일 창을
	 * 벗어난 주문들은 이 값을 가질 기회가 없었고, 그래서 화면이 반영 여부를 판정하지 못했다
	 * (창 안 주문은 전 마켓 100% 수집되고 있었다 — 진행 중 동작은 이미 일관됐다).
	 * 새 경로를 만들지 않고 <b>검증된 동기화 경로를 넓은 기간으로 한 번 더 돌리는</b> 방식을 쓴다.
	 */
	public void syncCoupangOrders(int lookbackDays) {
		syncCoupangOrders(LocalDate.now().minusDays(lookbackDays), LocalDate.now());
	}

	/** 조회 구간을 직접 지정한 동기화. 백필이 마켓 API 제약(범위 상한·레이트리밋)에 맞춰
	 *  구간을 나눠 걸을 때 쓴다. */
	public void syncCoupangOrders(LocalDate fromDate, LocalDate toDate) {
		syncCoupangOrders(fromDate, toDate, true);
	}

	/**
	 * @param createMissing 없는 주문을 새로 만들 것인가. 백필은 {@code false} — 과거 구간을 넓게 조회하면
	 *                      우리가 다룬 적 없는 옛 주문까지 들어와 주문 목록이 불어난다.
	 */
	@Transactional
	public void syncCoupangOrders(LocalDate fromDate, LocalDate toDate, boolean createMissing) {
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
			// 3. API 호출 → 주문 목록 획득. 부분 실패 여부를 함께 받는다(D-160).
			MarketFetchOutcome outcome = coupangOrderAdapter.fetchOrdersWithOutcome(
				credential, fromDate, toDate);
			List<MarketOrderDto> orders = outcome.orders();
			// 4. 주문 저장/업데이트
			processOrders(orders, credential, createMissing);
			// 5. 사후 처리 (취소감지, 반품완료 반영, 택배사 보정)
			postSyncProcess(orders, credential, fromDate, toDate, createMissing, outcome.complete());

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
	private void processOrders(List<MarketOrderDto> marketOrders, MarketCredential credential,
		boolean createMissing) {
		// 3단계: 어댑터가 3계층으로 내주지만 정규화기를 경계에 둔다 — 평면 DTO가 들어와도
		// 배송 1 : 상품주문 1로 감싸진다(설계 5.1).
		marketOrders = marketOrders.stream().map(MarketOrderNormalizer::normalize).toList();
		// F-SYNC-5: 기존/신규 판정·분기 골격만 공통 헬퍼에 위임. 갱신/생성의 쿠팡 고유 로직
		// (trackingSentToMarket 보존 가드, 정산액 ×0.89, sellerProductId 역조회 보강 등)은 아래 콜백에 그대로 남는다.
		MarketOrderUpsertDispatcher.dispatch(
			marketOrders, orderRepository, "COUPANG", this::updateExistingOrder, this::createNewOrder,
			createMissing);
	}

	/* ----- 기존 주문 업데이트 ----- */
	private void updateExistingOrder(Order order, MarketOrderDto dto) {
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
		updateOrderInfoFromDto(order, dto, lineItems);
		orderRepository.save(order);
		lineItemSyncDispatcher.sync(order, dto, lineItems, syncPolicy);
	}

	/* ----- 신규 주문 생성 ----- */
	private void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[COUPANG] 신규 주문 저장 완료: id={}, orderNo={}", order.getId(), order.getMarketOrderNo());
		lineItemSyncDispatcher.sync(order, dto, List.of(), syncPolicy);
	}

	/**
	 * 3단계 핵심: 배송박스를 배송으로 upsert하고 상품주문마다 라인아이템을 맞춰 넣는다.
	 *
	 * <p>매칭은 <b>주문 전체에서 한 번</b> 한다. 배송별로 나눠 매칭하면 상품주문이 다른 박스로
	 * 옮겨갔을 때 같은 기존 행을 두 배송이 각각 채택해 중복이 생긴다.
	 *
	 * <p>송장은 여기서 직접 쓰지 않는다 — 배송이 단일 원본이고 {@code linkToShipment}가 내려쓴다
	 * (설계 4.4, D-133). 진행상태·상품·정산액만 라인아이템에 반영한다.
	 */
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
			dto.getMarketType());
		// 통관번호는 실값일 때만 반영한다 — 마켓은 배송중·배송완료에서 이 필드를 빼거나 마스킹해 주고,
		// 한 번 지워지면 마켓에서 되받을 수 없어 복구가 불가능하다(도메인 가드가 정본).
		order.applyCustomsClearanceNoFromMarket(dto.getCustomsClearanceNo());
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
			.build();
	}

	/**
	 * 이 마켓의 3계층 동기화 정책. 골격이 갖지 않는 세 가지만 구현한다 —
	 * 로그 태그·상품 해석·라인아이템 생성(정산액 산출).
	 */
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

	/**
	 * 상품주문 1건의 라인아이템을 만든다.
	 *
	 * <p>송장은 넣지 않는다 — 배송이 단일 원본이고 미러가 내려쓴다(D-133). 정산액은 이 상품주문의
	 * 금액으로 계산한다. 종전엔 주문 전체가 한 행이라 첫 상품 금액만 반영됐다.
	 */
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

	/**
	 * 정산액은 마켓 실측값을 쓰고, 없을 때만 요율로 추정한다.
	 *
	 * <p>D-160에서 메서드로 뺐다 — 골격이 <b>거짓으로 0이 된 정산액을 되살릴 때</b>도 같은 산출을
	 * 써야 하는데, 종전엔 생성 경로 안에 파묻혀 있어 부를 수가 없었다.
	 */
	private BigDecimal resolveSettlementAmount(MarketLineItemDto dto) {
		if (dto.getSettlementAmount() != null && dto.getSettlementAmount().signum() != 0) {
			return dto.getSettlementAmount();
		}
		return marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.COUPANG);
	}

	/* ----- MarketRegistration → sb_productId 조회 ----- */
	private Long coupangResolveProductId(MarketLineItemDto dto) {
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

	/**
	 * 사후 처리.
	 *
	 * <p>D-160: 종전에는 조회 구간을 무시하고 <b>언제나 최근 30일</b>을 취소 판정 대상으로 삼았다.
	 * 백필이 과거 구간을 걸을 때마다 그 응답에 없는 최근 주문을 "사라졌다"로 읽어 거짓 취소를 만들었고,
	 * 뒤이어 정산액이 0으로 내려갔다(2026-08-08 라이브 사고, 쿠팡 종결 전 5건 손상).
	 *
	 * <p>부재로 상태를 단정하는 판정은 근거가 성립할 때만 한다 — <b>실제 조회한 구간</b> 안이고,
	 * <b>조회가 온전</b>했고, <b>상태 판정 권한이 있는 호출</b>(정기 동기화)일 때.
	 * 갱신 전용(백필)은 마켓 값을 채우러 가는 것이지 상태를 판정하러 가는 것이 아니다.
	 *
	 * @param fetchComplete 조회가 온전했는가. 부분 실패면 못 본 주문이 있을 수 있다
	 * @param createMissing {@code false}면 백필 — 상태 판정 권한이 없다
	 */
	private void postSyncProcess(List<MarketOrderDto> orders, MarketCredential credential,
		LocalDate fromDate, LocalDate toDate, boolean createMissing, boolean fetchComplete) {
		if (!createMissing) {
			log.info("[COUPANG] 갱신 전용 동기화 — 취소·반품 감지를 건너뛴다 ({}~{})", fromDate, toDate);
		} else if (!fetchComplete) {
			log.warn("[COUPANG] 부분 조회로 취소·반품 감지를 건너뛴다 ({}~{}) — 못 본 주문을 사라진 것으로 읽지 않는다",
				fromDate, toDate);
		} else {
			// 1. 조회한 구간 안에서, API에 없는 주문 → CANCELED 처리
			coupangOrderAdapter.detectCancellations(orders, fromDate, toDate);
			// 2. (D-097) 반품완료 전방 감지 → DELIVERED건을 RETURNED+정산0으로 전환
			coupangOrderAdapter.detectReturns(credential, fromDate, toDate);
		}
		// 3. (D-098) 취소 종결 lineItem 정산0 정규화. DB에서 파생되는 판정이라 조회 상태와 무관하게 멱등.
		terminalSettlementService.zeroSettlementForRefunded(MarketType.COUPANG);
		// 4. 택배사 ETC → 실제 택배사로 보정
		coupangOrderAdapter.fixCarriers(orders);
	}
}
