package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.ProductRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstClaimListApplicationTest {
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
		when(adapter.resolveMissingOrderState(anyString(), anyString()))
			.thenReturn(ElevenstOrderAdapter.MissingOrderState.empty());
	}

	@Test
	@DisplayName("D-278: 정기 동기화(조회 온전)는 클레임 목록 API를 조회 구간으로 호출한다")
	void regularSyncFetchesClaimListSignals() {
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of());
		when(adapter.fetchClaimListSignals(anyString(), any(), any())).thenReturn(Map.of());

		LocalDate from = LocalDate.now().minusDays(30);
		LocalDate to = LocalDate.now();
		service.syncElevenstOrders(from, to);

		verify(adapter).fetchClaimListSignals("api-key", from, to);
	}

	@Test
	@DisplayName("D-278: 갱신 전용(백필) 호출은 클레임 목록 API를 조회하지 않는다")
	void backfillDoesNotFetchClaimListSignals() {
		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now(), false);

		verify(adapter, never()).fetchClaimListSignals(anyString(), any(), any());
	}

	@Test
	@DisplayName("D-278: 부분 조회면 클레임 목록 API를 조회하지 않는다 — 못 본 것을 사라진 것으로 읽지 않는다")
	void partialFetchDoesNotFetchClaimListSignals() {
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(MarketFetchOutcome.partial(List.of()));

		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(adapter, never()).fetchClaimListSignals(anyString(), any(), any());
	}

	@Test
	@DisplayName("D-278: 클레임 목록 API 신호가 구간 내 주문의 라인아이템에 반영된다")
	void claimSignalsAreAppliedToMatchingLineItem() {
		Order order = liveOrder("20260809000000001");
		OrderLineItem item = lineItem(900L, "1");
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(900L)).thenReturn(List.of(item));

		ClaimData claim = ClaimData.builder()
			.claimType(ClaimType.RETURN).claimStage(ClaimStage.REQUESTED).claimRawCode("105").build();
		when(adapter.fetchClaimListSignals(anyString(), any(), any()))
			.thenReturn(Map.of("20260809000000001", Map.of("1", claim)));

		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getClaimData().getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(item.getClaimData().getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
	}

	private Order liveOrder(String marketOrderNo) {
		Order o = Order.builder().marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo(marketOrderNo)
			.orderDate(LocalDateTime.now().minusDays(1)).build();
		ReflectionTestUtils.setField(o, "id", 900L);
		return o;
	}

	private OrderLineItem lineItem(Long orderId, String seq) {
		return OrderLineItem.builder().orderId(orderId).quantity(1)
			.marketLineItemNo(seq)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.SHIPPED).build())
			.build();
	}
}
