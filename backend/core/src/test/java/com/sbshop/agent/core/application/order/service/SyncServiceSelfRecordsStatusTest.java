package com.sbshop.agent.core.application.order.service;

import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
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

@ExtendWith(MockitoExtension.class)
class SyncServiceSelfRecordsStatusTest {
	@Mock
	private Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private SyncStatusService syncStatusService;

	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

	private Cafe24OrderSyncService service;

	@BeforeEach
	void setUp() {
		service = new Cafe24OrderSyncService(cafe24OrderApiPort, orderRepository,
			orderLineItemRepository, new MarketRegistrationLookup(marketRegistrationRepository),
			eventPublisher, syncStatusService,
			marketFeeService,
			Mockito.mock(TerminalSettlementService.class),
			Mockito.mock(Cafe24ShipmentTrackingLookup.class),
			Mockito.mock(MarketLineItemSyncDispatcher.class));
	}

	@Test
	void onSuccess_recordsRunningThenCompleted() {
		lenient().when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(),
			ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
			.thenReturn(com.fasterxml.jackson.databind.node.MissingNode.getInstance());

		service.syncCafe24Orders();

		InOrder inOrder = inOrder(syncStatusService);
		inOrder.verify(syncStatusService).markRunning("GMARKET");
		inOrder.verify(syncStatusService).markCompleted("GMARKET");
	}

	@Test
	void onFailure_recordsFailedWithReason() {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(),
			ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
			.thenThrow(new RuntimeException("Cafe24 API 호출 실패"));

		service.syncCafe24Orders();

		verify(syncStatusService).markRunning("GMARKET");
		verify(syncStatusService).markFailed(eq("GMARKET"), contains("Cafe24 API 호출 실패"));
	}
}
