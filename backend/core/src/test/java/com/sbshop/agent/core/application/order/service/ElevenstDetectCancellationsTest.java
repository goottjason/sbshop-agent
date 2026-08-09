package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-028 회귀 방지: 11번가 postSyncProcess가 취소 감지를 하지 않아, 취소된 주문이 이전 상태로
 * 영구 잔류하던 결함을 고정한다. 쿠팡 정본 패턴(terminal = CANCELED·DELIVERED·RETURNED·EXCHANGED)을
 * 이식하여, API 응답에 없는 non-terminal 주문만 CANCELED 처리한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstDetectCancellationsTest {

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
	private ElevenstOrderAdapter elevenstOrderAdapter;
	@Mock
	private com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;
	@Mock
	private com.sbshop.agent.core.domain.order.repository.ShipmentRepository shipmentRepository;

	// 코드 기본요율(빈 정책 폴백)로 정산액을 계산하도록 실제 인스턴스 사용
	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

	private ElevenstOrderSyncService service() {
		return new ElevenstOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository,
			productRepository, eventPublisher, elevenstOrderAdapter, syncStatusService, marketFeeService,
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class),
			// 3계층 반영 골격. 이 테스트들은 라인아이템 반영을 검증하지 않으므로 목으로 둔다.
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.MarketLineItemSyncDispatcher.class),
			shipmentRepository, org.mockito.Mockito.mock(com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository.class));
	}

	private void stubCredentialAndEmptyApi() {
		MarketCredential credential = org.mockito.Mockito.mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(java.util.Optional.of(credential));
		// API 응답 비어 있음 → 모든 DB 주문이 조회 대상에 없는 상황. 조회 자체는 온전했다(부분 실패 아님).
		when(elevenstOrderAdapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(com.sbshop.agent.core.application.order.dto.MarketFetchOutcome.complete(List.of()));
	}

	private Order order(String orderNo) {
		return Order.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo(orderNo)
			.orderDate(LocalDateTime.now().minusDays(1))
			.build();
	}

	/** 주문 전체 키로 상태 하나만 담은 응답(순번 미상 라인아이템에 적용된다). */
	private com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter.MissingOrderState
		missingState(ShippingStatus status) {
		return new com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter.MissingOrderState(
			java.util.Map.of(
				com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter.CLAIM_ORDER_WIDE, status),
			java.util.Map.of());
	}

	private OrderLineItem item(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	@Test
	@DisplayName("[D-099] 사라진 NEW 주문의 실상태가 취소면 CANCELED로 처리된다(상세조회 판정)")
	void newOrder_absentFromApi_resolvedCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.NEW);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("A-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));
		when(elevenstOrderAdapter.resolveMissingOrderState(any(), any()))
			.thenReturn(missingState(ShippingStatus.CANCELED));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("[D-099] 사라진 주문의 실상태가 반품이면 RETURNED로 처리된다(취소로 뭉뚱그리지 않음)")
	void absentOrder_resolvedReturned() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("RT-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));
		when(elevenstOrderAdapter.resolveMissingOrderState(any(), any()))
			.thenReturn(missingState(ShippingStatus.RETURNED));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.RETURNED);
	}

	@Test
	@DisplayName("[D-099] 사라진 주문의 실상태가 교환이면 EXCHANGED로 처리된다")
	void absentOrder_resolvedExchanged() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("EX-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));
		when(elevenstOrderAdapter.resolveMissingOrderState(any(), any()))
			.thenReturn(missingState(ShippingStatus.EXCHANGED));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("[D-099] 사라졌지만 실상태가 클레임이 아니면(구매확정 등) 오취소하지 않고 상태 유지")
	void absentOrder_notAClaim_isNotCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("OK-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));
		// 상세조회가 구매확정 등 정상 상태 → 클레임 아님(null)
		// 클레임이 아니면 빈 결과 — 상태를 바꾸지 않는다(오취소 방지).
		when(elevenstOrderAdapter.resolveMissingOrderState(any(), any()))
			.thenReturn(com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter.MissingOrderState.empty());

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("[D-028] RETURNED 주문은 API 응답에 없어도 CANCELED로 오취소하지 않는다")
	void returnedOrder_absentFromApi_isNotCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.RETURNED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("R-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.RETURNED);
	}

	@Test
	@DisplayName("[D-028] EXCHANGED 주문은 API 응답에 없어도 CANCELED로 오취소하지 않는다")
	void exchangedOrder_absentFromApi_isNotCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.EXCHANGED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("E-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("[D-028] DELIVERED 주문은 취소 처리되지 않는다")
	void deliveredOrder_absentFromApi_isNotCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.DELIVERED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("D-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}
}
