package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.application.order.adapter.SmartStoreOrderAdapter;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
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
 * D-022 회귀 방지: 동기화 실패 시 finally 블록이 SYNC_COMPLETED(success=true)를 재발행하여
 * 에러 이벤트를 은폐하던 결함(4개 마켓 동일 패턴)을 고정한다.
 * 실패 경로에서는 success=true 이벤트가 절대 발행되어서는 안 되고,
 * 성공 경로에서는 success=true 이벤트가 정확히 유지되어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderSyncEventEmissionTest {

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
	private SmartStoreOrderAdapter smartStoreOrderAdapter;
	@Mock
	private CoupangOrderAdapter coupangOrderAdapter;
	@Mock
	private CoupangStatusMapper coupangStatusMapper;
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

	// ---- 실패 경로: success=true 이벤트가 발행되면 안 됨 (Red 대상) ----

	@Test
	@DisplayName("[D-022] 스마트스토어 동기화 실패 시 success=true SYNC_COMPLETED를 발행하지 않는다")
	void smartStore_failure_doesNotEmitSuccessCompleted() {
		when(credentialRepository.findByMarketType(MarketType.SMART_STORE)).thenReturn(Optional.empty());
		SmartStoreOrderSyncService service = new SmartStoreOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository, productRepository,
			eventPublisher, smartStoreOrderAdapter, syncStatusService, marketFeeService, org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class));

		service.syncSmartStoreOrders();

		List<SyncCompletedEvent> events = capturedEvents();
		assertThat(events).anyMatch(e -> !e.isSuccess());
		assertThat(events).noneMatch(SyncCompletedEvent::isSuccess);
	}

	@Test
	@DisplayName("[D-022] 쿠팡 동기화 실패 시 success=true SYNC_COMPLETED를 발행하지 않는다")
	void coupang_failure_doesNotEmitSuccessCompleted() {
		when(credentialRepository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.empty());
		CoupangOrderSyncService service = new CoupangOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository, productRepository,
			marketRegistrationRepository, eventPublisher, coupangOrderAdapter, coupangStatusMapper,
			syncStatusService, marketFeeService,
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class),
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.actionlog.ActionLogService.class),
			// 3단계: 배송 계층 upsert. 이 테스트들은 배송을 검증하지 않으므로 목으로 둔다.
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.OrderShipmentUpsertService.class));

		service.syncCoupangOrders();

		List<SyncCompletedEvent> events = capturedEvents();
		assertThat(events).anyMatch(e -> !e.isSuccess());
		assertThat(events).noneMatch(SyncCompletedEvent::isSuccess);
	}

	@Test
	@DisplayName("[D-022] 11번가 동기화 실패 시 success=true SYNC_COMPLETED를 발행하지 않는다")
	void elevenst_failure_doesNotEmitSuccessCompleted() {
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(Optional.empty());
		ElevenstOrderSyncService service = new ElevenstOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository, productRepository,
			eventPublisher, elevenstOrderAdapter, syncStatusService, marketFeeService,
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class),
			// 2단계: 배송 계층 upsert. 이 테스트들은 배송을 검증하지 않으므로 목으로 둔다.
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.OrderShipmentUpsertService.class));

		service.syncElevenstOrders();

		List<SyncCompletedEvent> events = capturedEvents();
		assertThat(events).anyMatch(e -> !e.isSuccess());
		assertThat(events).noneMatch(SyncCompletedEvent::isSuccess);
	}

	// ---- 성공 경로: success=true 이벤트가 정확히 발행되어야 함 (회귀 방지) ----

	@Test
	@DisplayName("[D-022] 스마트스토어 동기화 성공 시 success=true SYNC_COMPLETED가 정확히 한 번 발행된다")
	void smartStore_success_emitsExactlyOneSuccessCompleted() {
		MarketCredential credential = org.mockito.Mockito.mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("client");
		when(credential.getSecretKey()).thenReturn("secret");
		when(credentialRepository.findByMarketType(MarketType.SMART_STORE)).thenReturn(Optional.of(credential));
		when(smartStoreOrderAdapter.fetchOrders(any(), any(), any())).thenReturn(List.of());
		SmartStoreOrderSyncService service = new SmartStoreOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository, productRepository,
			eventPublisher, smartStoreOrderAdapter, syncStatusService, marketFeeService, org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class));

		service.syncSmartStoreOrders();

		List<SyncCompletedEvent> events = capturedEvents();
		assertThat(events).filteredOn(SyncCompletedEvent::isSuccess).hasSize(1);
		assertThat(events).noneMatch(e -> !e.isSuccess());
	}
}
