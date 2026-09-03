package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangReturnDetectionAlwaysRunsTest {
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
	private CoupangOrderAdapter adapter;
	@Mock
	private CoupangStatusMapper statusMapper;
	@Mock
	private SyncStatusService syncStatusService;
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private TerminalSettlementService terminalSettlementService;
	@Mock
	private ActionLogService actionLogService;
	@Mock
	private MarketLineItemSyncDispatcher lineItemSyncDispatcher;

	private CoupangOrderSyncService service;

	@BeforeEach
	void setUp() {
		service = new CoupangOrderSyncService(credentialRepository, orderRepository,
			orderLineItemRepository, productRepository, marketRegistrationRepository,
			new MarketRegistrationLookup(marketRegistrationRepository),
			eventPublisher, adapter, statusMapper, syncStatusService, marketFeeService,
			terminalSettlementService, actionLogService, lineItemSyncDispatcher);

		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("id");
		when(credential.getAccessKey()).thenReturn("access");
		when(credential.getSecretKey()).thenReturn("secret");
		when(credentialRepository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.of(credential));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of());
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(new MarketFetchOutcome(List.of(), true));
	}

	@Test
	@DisplayName("[D-265] 갱신 전용 호출에서도 반품 감지는 돈다 — 반품 API 는 증거이지 추정이 아니다")
	void detectReturnsRunsEvenInUpdateOnlyMode() {
		service.syncCoupangOrders(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 9, 1), false);

		verify(adapter).detectReturns(any(), any(), any());
		verify(adapter).detectExchanges(any(), any(), any());
	}

	@Test
	@DisplayName("[D-265] 부분 조회여도 반품 감지는 돈다 — 반품 API 는 목록 완전성과 무관하다")
	void detectReturnsRunsOnPartialFetch() {
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(new MarketFetchOutcome(List.of(), false));

		service.syncCoupangOrders(LocalDate.now().minusDays(30), LocalDate.now());

		verify(adapter).detectReturns(any(), any(), any());
		verify(adapter).detectExchanges(any(), any(), any());
	}
}
