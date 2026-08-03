package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * SP-1 (F-SYNC-2): @Async sync 메서드가 자신의 실행 상태(RUNNING/COMPLETED/FAILED)를 스스로 기록해야 한다.
 *
 * 기존엔 스케줄러가 @Async 메서드 호출 직후 곧바로 markCompleted를 불러(다른 스레드라 아직 안 끝났는데)
 * 조기 완료로 기록되고, async 스레드의 예외는 스케줄러 catch에 잡히지 않아 실패가 은폐됐다.
 * 상태 기록을 서비스 본문(async 스레드) 안으로 옮겨 진실성을 확보한다.
 */
@ExtendWith(MockitoExtension.class)
class SyncServiceSelfRecordsStatusTest {

	@Mock private Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private ApplicationEventPublisher eventPublisher;
	@Mock private SyncStatusService syncStatusService;

	// 코드 기본요율(빈 정책 폴백)로 정산액을 계산하도록 실제 인스턴스 사용
	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

	private Cafe24OrderSyncService service;

	@BeforeEach
	void setUp() {
		service = new Cafe24OrderSyncService(cafe24OrderApiPort, orderRepository,
			orderLineItemRepository, marketRegistrationRepository, eventPublisher, syncStatusService,
			marketFeeService, org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class),
			org.mockito.Mockito.mock(Cafe24ShipmentTrackingLookup.class));
	}

	@Test
	void onSuccess_recordsRunningThenCompleted() {
		lenient().when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(),
			org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
			.thenReturn(com.fasterxml.jackson.databind.node.MissingNode.getInstance());

		service.syncCafe24Orders();

		InOrder inOrder = inOrder(syncStatusService);
		inOrder.verify(syncStatusService).markRunning("GMARKET");
		inOrder.verify(syncStatusService).markCompleted("GMARKET");
	}

	@Test
	void onFailure_recordsFailedWithReason() {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(),
			org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
			.thenThrow(new RuntimeException("Cafe24 API 호출 실패"));

		service.syncCafe24Orders();

		verify(syncStatusService).markRunning("GMARKET");
		verify(syncStatusService).markFailed(eq("GMARKET"), contains("Cafe24 API 호출 실패"));
	}
}
