package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.application.order.adapter.SmartStoreOrderAdapter;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketCredentialValidationTest {
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
	private SmartStoreOrderAdapter smartStoreOrderAdapter;
	@Mock
	private ElevenstOrderAdapter elevenstOrderAdapter;
	@Mock
	private SyncStatusService syncStatusService;

	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

	@Test
	@DisplayName("스마트스토어: secret-key 빈 문자열이면 API 이전에 불완전 실패")
	void smartStore_emptySecret_failsFast() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("");
		when(credentialRepository.findByMarketType(MarketType.SMART_STORE)).thenReturn(Optional.of(c));

		SmartStoreOrderSyncService service = new SmartStoreOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository, productRepository,
			eventPublisher, smartStoreOrderAdapter, syncStatusService, marketFeeService,
			Mockito.mock(TerminalSettlementService.class),
			Mockito.mock(MarketLineItemSyncDispatcher.class));
		service.syncSmartStoreOrders();

		assertIncompleteCredentialFailure(capturedEvents());
	}

	@Test
	@DisplayName("11번가: access-key 공백이면 API 이전에 불완전 실패")
	void elevenst_blankAccessKey_failsFast() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getAccessKey()).thenReturn("   ");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(Optional.of(c));

		ElevenstOrderSyncService service = new ElevenstOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository, productRepository,
			eventPublisher, elevenstOrderAdapter, syncStatusService, marketFeeService,
			Mockito.mock(TerminalSettlementService.class),
			Mockito
				.mock(MarketLineItemSyncDispatcher.class),
			Mockito.mock(ShipmentRepository.class),
			Mockito
				.mock(MarketRegistrationLookup.class));
		service.syncElevenstOrders();

		assertIncompleteCredentialFailure(capturedEvents());
	}

	private List<SyncCompletedEvent> capturedEvents() {
		ArgumentCaptor<SyncCompletedEvent> captor = ArgumentCaptor.forClass(SyncCompletedEvent.class);
		verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
		return captor.getAllValues();
	}

	private void assertIncompleteCredentialFailure(List<SyncCompletedEvent> events) {
		assertThat(events).noneMatch(SyncCompletedEvent::isSuccess);
		assertThat(events).anyMatch(e -> !e.isSuccess()
			&& e.getErrorMessage() != null && e.getErrorMessage().contains("불완전"));
	}
}
