package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;

/**
 * D-046 (사이클 10 Batch B): 쿠팡 발행 시 marketIdentifiers에 sellerProductId만 저장되고,
 * 주문 매칭은 vendorItemId로 검색해 항상 미스매치 → OrderLineItem.productId=null 되던 결함을 고정한다.
 * 주문 동기화 시점에 sellerProductId 역조회 + vendorItemId 보강으로 매핑이 성립함을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CoupangOrderProductMappingTest {

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private CoupangOrderAdapter coupangOrderAdapter;
	@Mock
	private CoupangStatusMapper statusMapper;
	@Mock
	private com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;
	// 코드 기본요율(빈 정책 폴백)로 정산액을 계산하도록 실제 인스턴스를 @Spy로 주입
	@Spy
	private MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));
	private CoupangOrderSyncService service;

	@Mock
	private TerminalSettlementService terminalSettlementService;
	@Mock
	private com.sbshop.agent.core.application.actionlog.ActionLogService actionLogService;
	@Mock
	private ShipmentRepository shipmentRepository;

	/**
	 * 서비스를 <b>명시적으로 조립</b>한다. {@code @InjectMocks}는 목이 없는 의존성을 조용히 null로
	 * 남기고(이 테스트에서 terminalSettlementService가 그랬다) 무엇이 실제로 주입됐는지 감춘다.
	 *
	 * <p>배송 upsert 통로는 진짜 객체를 끼운다 — 목이면 배송 생성이 사라져 라인아이템 저장까지
	 * 도달하지 못하고, 검증이 통과해도 아무것도 증명하지 못한다(D-133에서 세운 규율).
	 */
	@BeforeEach
	void assembleService() {
		lenient().when(shipmentRepository.findByOrderIdAndMarketShipmentNo(any(), anyString()))
			.thenReturn(Optional.empty());
		lenient().when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
			Shipment sh = inv.getArgument(0);
			if (sh.getId() == null) {
				ReflectionTestUtils.setField(sh, "id", 91L);
			}
			return sh;
		});
		lenient().when(orderLineItemRepository.findByShipmentId(any())).thenReturn(List.of());

		service = new CoupangOrderSyncService(credentialRepository, orderRepository,
			orderLineItemRepository, productRepository, marketRegistrationRepository, eventPublisher,
			coupangOrderAdapter, statusMapper, syncStatusService, marketFeeService,
			terminalSettlementService, actionLogService,
			new MarketLineItemSyncDispatcher(orderLineItemRepository,
				new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository)));
	}

	private static final String VENDOR_ITEM_ID = "VI456";
	private static final String SELLER_PRODUCT_ID = "SP123";

	private MarketOrderDto newCoupangOrderDto() {
		return MarketOrderDto.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("ORD-1")
			.marketProductCode(VENDOR_ITEM_ID) // 주문 API가 준 vendorItemId
			.sellerProductId(SELLER_PRODUCT_ID) // 발행 시 저장된 식별자
			.quantity(1)
			.orderPrice(new BigDecimal("10000"))
			.totalAmount(new BigDecimal("10000"))
			.status(ShippingStatus.NEW)
			.orderDate(LocalDateTime.now())
			.build();
	}

	private void stubCredentialAndFetch(MarketOrderDto dto) {
		MarketCredential credential = MarketCredential.builder()
			.marketType(MarketType.COUPANG)
			.clientId("c").accessKey("a").secretKey("s")
			.build();
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(credential));
		when(coupangOrderAdapter.fetchOrdersWithOutcome(any(), any(LocalDate.class), any(LocalDate.class)))
			.thenReturn(new com.sbshop.agent.core.application.order.dto.MarketFetchOutcome(List.of(dto), true));
		when(orderRepository.findByMarketOrderNo("ORD-1")).thenReturn(Optional.empty());
	}

	@Test
	@DisplayName("[D-046] vendorItemId 미저장 상태에서 sellerProductId 역조회로 productId를 매칭하고 vendorItemId를 보강한다")
	void resolvesProductId_viaSellerProductIdFallback_andBackfillsVendorItemId() {
		MarketOrderDto dto = newCoupangOrderDto();
		stubCredentialAndFetch(dto);

		// vendorItemId LIKE 검색은 실패 (저장 식별자엔 sellerProductId만 존재 = 결함 상황)
		when(marketRegistrationRepository
			.findByMarketTypeAndIdentifiersContaining(MarketType.COUPANG, VENDOR_ITEM_ID))
			.thenReturn(List.of());
		// sellerProductId LIKE 검색은 성공
		MarketRegistration reg = MarketRegistration.builder()
			.productId(99L).sbProductId(99L).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"" + SELLER_PRODUCT_ID + "\"}")
			.build();
		when(marketRegistrationRepository
			.findByMarketTypeAndIdentifiersContaining(MarketType.COUPANG, SELLER_PRODUCT_ID))
			.thenReturn(List.of(reg));

		service.syncCoupangOrders();

		// 1) OrderLineItem이 매칭된 productId로 저장됨 (결함 시 null)
		ArgumentCaptor<OrderLineItem> captor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(captor.capture());
		assertThat(captor.getValue().getProductId()).isEqualTo(99L);

		// 2) vendorItemId가 레지스트레이션에 실제로 보강되어 이후 동기화부터 직접 매칭 가능
		assertThat(reg.extractVendorItemId()).isEqualTo(VENDOR_ITEM_ID);
		verify(marketRegistrationRepository).save(reg);
	}

	@Test
	@DisplayName("[D-046] vendorItemId가 이미 저장돼 있으면 기존 직접 매칭 경로를 쓰고 보강/역조회를 하지 않는다 (회귀 방지)")
	void whenVendorItemIdAlreadyStored_usesDirectMatch_noBackfill() {
		MarketOrderDto dto = newCoupangOrderDto();
		stubCredentialAndFetch(dto);

		MarketRegistration reg = MarketRegistration.builder()
			.productId(77L).sbProductId(77L).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"" + SELLER_PRODUCT_ID
				+ "\",\"vendorItemId\":\"" + VENDOR_ITEM_ID + "\"}")
			.build();
		when(marketRegistrationRepository
			.findByMarketTypeAndIdentifiersContaining(MarketType.COUPANG, VENDOR_ITEM_ID))
			.thenReturn(List.of(reg));

		service.syncCoupangOrders();

		ArgumentCaptor<OrderLineItem> captor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(captor.capture());
		assertThat(captor.getValue().getProductId()).isEqualTo(77L);

		// 직접 매칭 성공 시 sellerProductId 역조회·save 보강을 하지 않는다
		verify(marketRegistrationRepository, never())
			.findByMarketTypeAndIdentifiersContaining(eq(MarketType.COUPANG), eq(SELLER_PRODUCT_ID));
		verify(marketRegistrationRepository, never()).save(any(MarketRegistration.class));
	}
}
