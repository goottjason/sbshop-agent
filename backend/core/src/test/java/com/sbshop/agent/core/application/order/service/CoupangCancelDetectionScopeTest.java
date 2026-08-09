package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;

/**
 * D-160: <b>취소 감지의 사정거리</b>를 고정한다.
 *
 * <p>2026-08-08 라이브 사고: D-159 백필이 30일 구간을 오래된 쪽부터 걸으며 구간마다 동기화를
 * 호출했는데, 사후처리가 조회 구간을 무시하고 <b>언제나 최근 30일</b>을 취소 판정 대상으로 삼았다.
 * 그래서 4월 구간을 조회한 결과에 최근 주문이 없는 것을 "마켓에서 사라졌다"로 읽어
 * 종결 전 쿠팡 주문을 전부 CANCELED로 만들었고, 뒤이어 정산액이 0으로 내려갔다.
 * 상태는 다음 구간이 복원했지만 <b>정산액을 되돌리는 주체가 없어</b> 0이 영구히 남았다.
 *
 * <p>부재(absence)로 상태를 단정하는 판정은 그 근거가 성립하는 범위에서만 유효하다 —
 * 조회한 구간 안, 조회가 온전했을 때, 그리고 상태 판정 권한이 있는 호출에서만.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangCancelDetectionScopeTest {

	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private ProductRepository productRepository;
	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private ApplicationEventPublisher eventPublisher;
	@Mock private CoupangOrderAdapter adapter;
	@Mock private com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper statusMapper;
	@Mock private SyncStatusService syncStatusService;
	@Mock private MarketFeeService marketFeeService;
	@Mock private TerminalSettlementService terminalSettlementService;
	@Mock private ActionLogService actionLogService;
	@Mock private MarketLineItemSyncDispatcher lineItemSyncDispatcher;

	private CoupangOrderSyncService service;

	@BeforeEach
	void setUp() {
		service = new CoupangOrderSyncService(credentialRepository, orderRepository,
			orderLineItemRepository, productRepository, marketRegistrationRepository,
			eventPublisher, adapter, statusMapper, syncStatusService, marketFeeService,
			terminalSettlementService, actionLogService, lineItemSyncDispatcher);

		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("id");
		when(credential.getAccessKey()).thenReturn("access");
		when(credential.getSecretKey()).thenReturn("secret");
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(credential));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of());
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(new CoupangOrderAdapter.FetchOutcome(List.of(), true));
	}

	@Test
	@DisplayName("[D-160] 취소 감지는 실제 조회한 구간에만 적용한다 — 백필 구간이 최근 주문을 취소하지 않는다")
	void detectsCancellationsOnlyWithinTheQueriedWindow() {
		LocalDate from = LocalDate.of(2026, 4, 1);
		LocalDate to = LocalDate.of(2026, 4, 30);

		service.syncCoupangOrders(from, to);

		// 4월 구간을 조회했으면 4월만 판정한다. 조회하지 않은 최근 30일을 판정 대상에 넣는 순간
		// "응답에 없다 = 취소됐다"는 근거가 무너진다.
		verify(adapter).detectCancellations(any(), org.mockito.ArgumentMatchers.eq(from),
			org.mockito.ArgumentMatchers.eq(to));
	}

	@Test
	@DisplayName("[D-160] 갱신 전용(백필) 호출은 취소 감지를 하지 않는다 — 백필에 상태 판정 권한은 없다")
	void backfillDoesNotJudgeCancellation() {
		service.syncCoupangOrders(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), false);

		verify(adapter, never()).detectCancellations(any(), any(), any());
	}

	@Test
	@DisplayName("[D-160] 부분 조회 실패면 취소 감지를 건너뛴다 — 못 본 것을 사라진 것으로 읽지 않는다")
	void skipsCancellationDetectionOnPartialFetch() {
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(new CoupangOrderAdapter.FetchOutcome(List.of(), false));

		service.syncCoupangOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(adapter, never()).detectCancellations(any(), any(), any());
	}

	@Test
	@DisplayName("[D-160] 정기 동기화(조회 온전)는 종전대로 취소 감지를 한다 — 가드가 정상 경로를 막지 않는다")
	void regularSyncStillDetectsCancellations() {
		service.syncCoupangOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(adapter).detectCancellations(any(), any(), any());
	}

	@Test
	@DisplayName("[D-160] 조회가 온전하지 않아도 정산0 정규화는 계속 돈다 — DB에서 파생되는 판정이다")
	void terminalSettlementRunsRegardlessOfFetchCompleteness() {
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(new CoupangOrderAdapter.FetchOutcome(List.of(), false));

		service.syncCoupangOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(terminalSettlementService).zeroSettlementForRefunded(MarketType.COUPANG);
	}
}
