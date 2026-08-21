package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

/**
 * D-160(11번가 확장): 클레임 감지의 <b>사정거리</b>를 고정한다.
 *
 * <p>쿠팡에서 실제 피해를 낸 구조가 11번가에도 그대로 있었다 — {@code postSyncProcess}가 조회 구간을
 * 무시하고 언제나 {@code now-30d}를 판정 대상으로 삼는다. 11번가는 {@code detectClaims}가
 * <b>단건 상세조회로 확증</b>하므로 거짓 취소로 번지지는 않았다(D-099). 그래서 피해는 없었다.
 *
 * <p>그러나 근거 없는 판정 자체는 공짜가 아니다. 백필이 4월 구간을 걸을 때마다 <b>그 응답에 없는
 * 최근 주문 전부</b>에 단건 상세조회를 때린다 — 11번가는 레이트리밋 때문에 백필이 구간 사이에
 * 쉬어야 하는 마켓이다. 확증 단계가 그 비용을 치르고 있을 뿐, 판정을 시도해선 안 되는 범위다.
 *
 * <p>규율은 쿠팡과 같다: 조회한 구간 안에서 · 조회가 온전했을 때 · 상태 판정 권한이 있는 호출에서만.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstClaimDetectionScopeTest {

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
			lineItemSyncDispatcher, shipmentRepository, marketRegistrationRepository);

		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(Optional.of(credential));
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(MarketFetchOutcome.complete(List.of()));
		// 조회 구간 밖(오늘)에 있는 종결 전 주문 하나 — 백필이 과거 구간을 걸을 때의 상황이다.
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(List.of(recentLiveOrder()));
		when(orderLineItemRepository.findByOrderId(any()))
			.thenReturn(List.of(nonTerminalItem()));
	}

	private Order recentLiveOrder() {
		Order o = Order.builder().marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo("20260809000000001")
			.orderDate(LocalDateTime.now().minusDays(1)).build();
		ReflectionTestUtils.setField(o, "id", 900L);
		return o;
	}

	private OrderLineItem nonTerminalItem() {
		return OrderLineItem.builder().orderId(900L).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.PREPARING).build())
			.build();
	}

	@Test
	@DisplayName("[D-160] 백필 구간은 구간 밖 최근 주문에 상세조회를 때리지 않는다 — 판정 범위가 아니다")
	void doesNotProbeOrdersOutsideTheQueriedWindow() {
		service.syncElevenstOrders(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));

		verify(adapter, never()).resolveMissingOrderState(anyString(), anyString());
	}

	@Test
	@DisplayName("[D-160] 갱신 전용(백필) 호출은 클레임 감지를 하지 않는다 — 백필에 상태 판정 권한은 없다")
	void backfillDoesNotJudgeClaims() {
		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now(), false);

		verify(adapter, never()).resolveMissingOrderState(anyString(), anyString());
	}

	@Test
	@DisplayName("[D-160] 부분 조회 실패면 클레임 감지를 건너뛴다 — 못 본 것을 사라진 것으로 읽지 않는다")
	void skipsClaimDetectionOnPartialFetch() {
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(MarketFetchOutcome.partial(List.of()));

		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(adapter, never()).resolveMissingOrderState(anyString(), anyString());
	}

	@Test
	@DisplayName("[D-160] 정기 동기화(조회 온전)는 종전대로 클레임을 확증한다 — 가드가 정상 경로를 막지 않는다")
	void regularSyncStillProbesMissingOrders() {
		when(adapter.resolveMissingOrderState(anyString(), anyString()))
			.thenReturn(ElevenstOrderAdapter.MissingOrderState.empty());

		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(adapter).resolveMissingOrderState(anyString(),
			org.mockito.ArgumentMatchers.eq("20260809000000001"));
	}

	@Test
	@DisplayName("[D-160] 조회가 온전하지 않아도 정산0 정규화는 계속 돈다 — DB에서 파생되는 판정이다")
	void terminalSettlementRunsRegardlessOfFetchCompleteness() {
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(MarketFetchOutcome.partial(List.of()));

		service.syncElevenstOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(terminalSettlementService).zeroSettlementForRefunded(MarketType.ELEVEN_STREET);
	}
}
