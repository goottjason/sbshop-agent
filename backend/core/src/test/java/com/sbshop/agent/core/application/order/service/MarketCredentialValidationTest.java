package com.sbshop.agent.core.application.order.service;

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

/**
 * D-043: 빈 문자열/공백 자격증명을 API 호출 이전에 fast-fail로 표면화하는지 검증.
 * 수정 전에는 빈 secret/masterId가 검증을 통과해 "성공 0건"으로 위장되던 것을 고정한다.
 */
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
	private com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;

	// 코드 기본요율(빈 정책 폴백)로 정산액을 계산하도록 실제 인스턴스 사용
	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

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

	@Test
	@DisplayName("스마트스토어: secret-key 빈 문자열이면 API 이전에 불완전 실패")
	void smartStore_emptySecret_failsFast() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("");
		when(credentialRepository.findByMarketType(MarketType.SMART_STORE)).thenReturn(Optional.of(c));

		SmartStoreOrderSyncService service = new SmartStoreOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository, productRepository,
			eventPublisher, smartStoreOrderAdapter, syncStatusService, marketFeeService, org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class));
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
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class),
			// 2단계: 배송 계층 upsert. 이 테스트들은 배송을 검증하지 않으므로 목으로 둔다.
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.OrderShipmentUpsertService.class));
		service.syncElevenstOrders();

		assertIncompleteCredentialFailure(capturedEvents());
	}
}
