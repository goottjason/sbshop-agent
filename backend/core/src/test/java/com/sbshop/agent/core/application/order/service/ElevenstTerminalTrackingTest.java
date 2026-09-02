package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.ProductRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstTerminalTrackingTest {

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private ElevenstOrderAdapter adapter;
	@Mock
	private SyncStatusService syncStatusService;
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private TerminalSettlementService terminalSettlementService;
	@Mock
	private MarketLineItemSyncDispatcher lineItemSyncDispatcher;
	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;

	private ElevenstOrderSyncService service;

	private static final String ORDER_NO = "20260809000000001";

	@BeforeEach
	void setUp() {
		service = new ElevenstOrderSyncService(credentialRepository, orderRepository,
			orderLineItemRepository, productRepository, eventPublisher, adapter,
			syncStatusService, marketFeeService, terminalSettlementService,
			lineItemSyncDispatcher, shipmentRepository,
			new MarketRegistrationLookup(marketRegistrationRepository));

		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(Optional.of(credential));
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(MarketFetchOutcome.complete(List.of()));
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(List.of(order()));
	}

	private Order order() {
		Order o = Order.builder().marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo(ORDER_NO)
			.orderDate(LocalDateTime.now().minusDays(1)).build();
		ReflectionTestUtils.setField(o, "id", 900L);
		return o;
	}

	private OrderLineItem item(ShippingStatus status) {
		return OrderLineItem.builder().orderId(900L).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	@Test
	@DisplayName("배송완료 주문도 목록에서 사라지면 다시 확인한다 — 배송완료 뒤에 반품이 들어온다")
	void deliveredOrderIsStillProbed() {
		when(orderLineItemRepository.findByOrderId(any()))
			.thenReturn(List.of(item(ShippingStatus.DELIVERED)));
		when(adapter.resolveMissingOrderState(anyString(), anyString()))
			.thenReturn(ElevenstOrderAdapter.MissingOrderState.empty());

		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(adapter).resolveMissingOrderState(anyString(), eq(ORDER_NO));
	}

	@Test
	@DisplayName("반품 주문도 다시 확인한다 — 고객 변심으로 배송완료로 되돌아온다")
	void returnedOrderIsStillProbed() {
		when(orderLineItemRepository.findByOrderId(any()))
			.thenReturn(List.of(item(ShippingStatus.RETURNED)));
		when(adapter.resolveMissingOrderState(anyString(), anyString()))
			.thenReturn(ElevenstOrderAdapter.MissingOrderState.empty());

		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(adapter).resolveMissingOrderState(anyString(), eq(ORDER_NO));
	}

	@Test
	@DisplayName("종결로 보던 아이템에도 마켓이 준 새 상태를 반영한다 — 반품이 배송완료로 뒤집힌다")
	void terminalItemStillGetsUpdated() {
		OrderLineItem returned = item(ShippingStatus.RETURNED);
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(returned));
		when(adapter.resolveMissingOrderState(anyString(), anyString()))
			.thenReturn(new ElevenstOrderAdapter.MissingOrderState(
				Map.of(ElevenstOrderAdapter.CLAIM_ORDER_WIDE, ShippingStatus.DELIVERED),
				Map.of()));

		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(orderLineItemRepository).save(returned);
	}

	@Test
	@DisplayName("창 밖 주문은 여전히 건드리지 않는다 — terminal 폐기가 판정 범위까지 넓히지는 않는다")
	void outsideWindowStillUntouched() {
		when(orderLineItemRepository.findByOrderId(any()))
			.thenReturn(List.of(item(ShippingStatus.DELIVERED)));

		service.syncElevenstOrders(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));

		verify(adapter, never()).resolveMissingOrderState(anyString(), anyString());
	}
}
