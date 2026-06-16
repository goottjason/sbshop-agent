package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 동기화 서비스의 공통 템플릿 메소드를 정의한 추상 클래스
 * 각 마켓 어댑터는 이 클래스를 상속받아 구체적인 동기화 로직을 구현
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractOrderSyncService {

	protected final MarketCredentialRepository credentialRepository;
	protected final OrderRepository orderRepository;
	protected final OrderLineItemRepository orderLineItemRepository;
	protected final ProductRepository productRepository;
	protected final MarketRegistrationRepository marketRegistrationRepository;
	protected final ApplicationEventPublisher eventPublisher;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	/**
	 * 템플릿 메소드: 주문 동기화 실행
	 * 중복 실행 방지 -> 크레덴셜 로드 -> 주문 조회 -> 후처리
	 */
	@Async("syncTaskExecutor")
	@Transactional
	public final void syncOrders() {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[{}] 동기화 중복 실행 방지", getMarketType());
			return;
		}

		try {
			MarketCredential credential = loadAndValidateCredential();
			MarketOrderPort port = getPort();

			List<MarketOrderDto> orders = port.fetchOrders(credential,
				java.time.LocalDate.now().minusDays(30),
				java.time.LocalDate.now());

			processOrders(orders, credential, port);
			postSyncProcess(orders);

			log.info("[{}] 주문 동기화 완료: {}건 처리", getMarketType(), orders.size());

		} catch (Exception e) {
			log.error("[{}] 주문 동기화 실패: {}", getMarketType(), e.getMessage(), e);
			eventPublisher.publishEvent(
				new SyncCompletedEvent(this, getMarketType(), false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			eventPublisher.publishEvent(new SyncCompletedEvent(this, getMarketType()));
		}
	}

	/**
	 * 크레덴셜 로드 및 검증
	 */
	protected MarketCredential loadAndValidateCredential() {
		MarketCredential credential = credentialRepository.findByMarketType(getMarketType())
			.orElseThrow(() -> new IllegalArgumentException(
				getMarketType() + " 크레덴셜 없음"));

		validateCredential(credential);
		return credential;
	}

	/**
	* 처리 중인 주문의 상세 조회가 항상 필요한지 여부
	* ESM+ 등 리스트 API에 전화번호/주소가 없는 마켓은 true 오버라이드
	*/
	protected boolean alwaysFetchDetail() {
		return false;
	}

	/**
	 * 주문 목록 처리: 기존 주문 업데이트 또는 신규 주문 생성
	 */
	protected void processOrders(List<MarketOrderDto> marketOrders,
		MarketCredential credential, MarketOrderPort port) {
		for (MarketOrderDto dto : marketOrders) {
			log.info("[{}] 처리 중: orderNo={}, status={}",
				getMarketType(), dto.getMarketOrderNo(), dto.getStatus());
			Optional<Order> existingOrder = orderRepository.findByMarketOrderNo(dto.getMarketOrderNo());

			boolean shouldFetchDetail = alwaysFetchDetail();

			if (existingOrder.isPresent() && shouldFetchDetail) {
				log.info("[{}] 기존 주문 + 상세 조회 시도: orderNo={}",
					getMarketType(), dto.getMarketOrderNo());
				MarketOrderDto fullDto = port.fetchOrderDetail(credential, dto);
				if (fullDto != null) {
					log.info("[{}] 상세 조회 성공 - 기존 주문 업데이트: id={}, orderNo={}",
						getMarketType(), existingOrder.get().getId(), dto.getMarketOrderNo());
					updateExistingOrder(existingOrder.get(), fullDto);
				} else {
					log.warn("[{}] 상세 조회 실패 - 상태/운송장만 업데이트: orderNo={}",
						getMarketType(), dto.getMarketOrderNo());
					updateExistingOrder(existingOrder.get(), dto);
				}
			} else if (existingOrder.isPresent()) {
				log.info("[{}] 기존 주문 발견: id={}, orderNo={}", getMarketType(), existingOrder.get().getId(),
					dto.getMarketOrderNo());
				updateExistingOrder(existingOrder.get(), dto);
			} else if (shouldFetchDetail) {
				log.info("[{}] 최소 데이터 주문 - 상세 조회 시도: orderNo={}", getMarketType(), dto.getMarketOrderNo());
				MarketOrderDto fullDto = port.fetchOrderDetail(credential, dto);
				if (fullDto != null) {
					log.info("[{}] 상세 조회 성공 - 신규 주문 생성: orderNo={}", getMarketType(), dto.getMarketOrderNo());
					createNewOrder(fullDto);
				} else {
					log.warn("[{}] 상세 조회 실패 - 기본 데이터로 신규 주문 생성: orderNo={}",
						getMarketType(), dto.getMarketOrderNo());
					createNewOrder(dto);
				}
			} else {
				log.info("[{}] 신규 주문 생성 시도: orderNo={}", getMarketType(), dto.getMarketOrderNo());
				createNewOrder(dto);
			}
		}
	}

	/**
	 * 기존 주문 업데이트
	 */
	protected void updateExistingOrder(Order order, MarketOrderDto dto) {
		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());

		for (OrderLineItem item : lineItems) {
			updateLineItemFromDto(item, dto);
		}

		updateOrderInfoFromDto(order, dto);
		orderRepository.save(order);
		lineItems.forEach(orderLineItemRepository::save);
	}

	/**
	 * 라인 아이템 업데이트 (기본 구현, 오버라이드 가능)
	 */
	protected void updateLineItemFromDto(OrderLineItem item, MarketOrderDto dto) {
		Long productId = resolveProductId(dto);
		if (productId != null && !productId.equals(item.getProductId())) {
			item.assignProductId(productId);
		}
		item.updateShippingWithCarrier(
			dto.getTrackingNo(),
			dto.getStatus(),
			item.getShippingData() != null ? item.getShippingData().getIsUnipassDone() : null,
			dto.getCarrier());
	}

	/**
	 * 주문 정보 업데이트 (기본 구현, 오버라이드 가능)
	 */
	protected void updateOrderInfoFromDto(Order order, MarketOrderDto dto) {
		log.info("[{}] updateOrderInfoFromDto: dto.marketType={}, order.marketType={}, orderNo={}",
			getMarketType(), dto.getMarketType(), order.getMarketType(), dto.getMarketOrderNo());
		if (dto.getMarketType() != null && dto.getMarketType() != order.getMarketType()) {
			log.info("[{}] marketType 업데이트: {} → {} (orderNo={})",
				getMarketType(), order.getMarketType(), dto.getMarketType(), dto.getMarketOrderNo());
			order.updateMarketType(dto.getMarketType());
		}
		order.updateInfo(
			dto.getRecipientName(),
			dto.getRecipientPhone(),
			dto.getZipcode(),
			dto.getAddress(),
			dto.getMessage());

		if (dto.getOrdererName() != null) {
			order.updateOrdererInfo(dto.getOrdererName(), dto.getOrdererPhone());
		}

		if (dto.getCustomsClearanceNo() != null) {
			order.updateCustomsClearanceNo(dto.getCustomsClearanceNo());
		}

		if (dto.getShipmentBoxId() != null) {
			order.updateShipmentBoxId(dto.getShipmentBoxId());
		}
	}

	/**
	 * 신규 주문 생성
	 */
	protected void createNewOrder(MarketOrderDto dto) {
		Order order = buildOrderFromDto(dto);
		orderRepository.save(order);
		log.info("[{}] 신규 주문 저장 완료: id={}, orderNo={}, marketType={}",
			getMarketType(), order.getId(), order.getMarketOrderNo(), order.getMarketType());

		OrderLineItem lineItem = buildLineItemFromDto(dto, order.getId());
		orderLineItemRepository.save(lineItem);
	}

	/**
	 * 주문 엔티티 빌드 (기본 구현, 오버라이드 가능)
	 */
	protected Order buildOrderFromDto(MarketOrderDto dto) {
		MarketType marketType = dto.getMarketType();
		return Order.builder()
			.marketType(marketType)
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

	/**
	 * 라인 아이템 엔티티 빌드 (기본 구현, 오버라이드 가능)
	 */
	protected OrderLineItem buildLineItemFromDto(MarketOrderDto dto, Long orderId) {
		Long productId = resolveProductId(dto);

		BigDecimal settlementAmount = dto.getMarketType() == MarketType.COUPANG && dto.getTotalAmount() != null
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

	private Long resolveProductId(MarketOrderDto dto) {
		if (dto.getMarketType() == MarketType.COUPANG && dto.getMarketProductCode() != null) {
			List<MarketRegistration> regs = marketRegistrationRepository
				.findByMarketTypeAndIdentifiersContaining(MarketType.COUPANG, dto.getMarketProductCode());
			if (!regs.isEmpty()) {
				Long sbProductId = regs.get(0).getSbProductId();
				log.info("[{}] sb_market_registration에서 productId 조회: vendorItemId={}, sbProductId={}",
					getMarketType(), dto.getMarketProductCode(), sbProductId);
				return sbProductId;
			}
			log.warn("[{}] sb_market_registration에서 productId를 찾을 수 없음: vendorItemId={}",
				getMarketType(), dto.getMarketProductCode());
			return null;
		}
		if (dto.getMarketProductCode() != null) {
			Product product = productRepository.findBySbCode(dto.getMarketProductCode()).orElse(null);
			return product != null ? product.getId() : null;
		}
		return null;
	}

	/**
	 * 세관 데이터 빌드 (기본 구현, 오버라이드 가능)
	 */
	protected CustomsData buildCustomsData(MarketOrderDto dto) {
		if (dto.getCustomsClearanceNo() != null && !dto.getCustomsClearanceNo().trim().isEmpty()) {
			return CustomsData.builder()
				.customsClearanceNo(dto.getCustomsClearanceNo())
				.build();
		}
		return null;
	}

	/**
	 * 후처리 로직 (기본 구현은 없음, 오버라이드로 구현)
	 */
	protected void postSyncProcess(List<MarketOrderDto> orders) {
		// 기본 구현: 후처리 없음
	}

	/**
	 * 추상 메서드: 마켓 타입 식별
	 */
	protected abstract MarketType getMarketType();

	/**
	 * 추상 메서드: 마켓 주문 포트 반환
	 */
	protected abstract MarketOrderPort getPort();

	/**
	 * 추상 메서드: 크레덴셜 검증
	 */
	protected abstract void validateCredential(MarketCredential credential);
}
