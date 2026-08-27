package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.sync.SyncStatusService;
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
import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
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
	private SyncStatusService syncStatusService;
	@Spy
	private MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));
	private CoupangOrderSyncService service;

	@Mock
	private TerminalSettlementService terminalSettlementService;
	@Mock
	private ActionLogService actionLogService;
	@Mock
	private ShipmentRepository shipmentRepository;

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
			orderLineItemRepository, productRepository, marketRegistrationRepository,
			new MarketRegistrationLookup(marketRegistrationRepository), eventPublisher,
			coupangOrderAdapter, statusMapper, syncStatusService, marketFeeService,
			terminalSettlementService, actionLogService,
			new MarketLineItemSyncDispatcher(orderLineItemRepository,
				new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository)));
	}

	private static final String VENDOR_ITEM_ID = "VI456";
	private static final String SELLER_PRODUCT_ID = "SP123";

	@Test
	@DisplayName("[D-046] vendorItemId 미저장 상태에서 sellerProductId 역조회로 productId를 매칭하고 vendorItemId를 보강한다")
	void resolvesProductId_viaSellerProductIdFallback_andBackfillsVendorItemId() {
		MarketOrderDto dto = newCoupangOrderDto();
		stubCredentialAndFetch(dto);

		when(marketRegistrationRepository
			.findIdentifierCandidates(MarketType.COUPANG, VENDOR_ITEM_ID))
			.thenReturn(List.of());
		MarketRegistration reg = MarketRegistration.builder()
			.productId(99L).sbProductId(99L).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"" + SELLER_PRODUCT_ID + "\"}")
			.build();
		when(marketRegistrationRepository
			.findIdentifierCandidates(MarketType.COUPANG, SELLER_PRODUCT_ID))
			.thenReturn(List.of(reg));

		service.syncCoupangOrders();

		ArgumentCaptor<OrderLineItem> captor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(captor.capture());
		assertThat(captor.getValue().getProductId()).isEqualTo(99L);

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
			.findIdentifierCandidates(MarketType.COUPANG, VENDOR_ITEM_ID))
			.thenReturn(List.of(reg));

		service.syncCoupangOrders();

		ArgumentCaptor<OrderLineItem> captor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(captor.capture());
		assertThat(captor.getValue().getProductId()).isEqualTo(77L);

		verify(marketRegistrationRepository, never())
			.findIdentifierCandidates(eq(MarketType.COUPANG), eq(SELLER_PRODUCT_ID));
		verify(marketRegistrationRepository, never()).save(any(MarketRegistration.class));
	}

	private MarketOrderDto newCoupangOrderDto() {
		return MarketOrderDto.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("ORD-1")
			.marketProductCode(VENDOR_ITEM_ID)
			.sellerProductId(SELLER_PRODUCT_ID)
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
			.thenReturn(new MarketFetchOutcome(List.of(dto), true));
		when(orderRepository.findByMarketOrderNo("ORD-1")).thenReturn(Optional.empty());
	}
}
