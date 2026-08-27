package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import java.util.Map;
import java.util.Optional;
import org.mockito.Mockito;
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
	private SyncStatusService syncStatusService;
	@Mock
	private ShipmentRepository shipmentRepository;

	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

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
		when(elevenstOrderAdapter.resolveMissingOrderState(any(), any()))
			.thenReturn(ElevenstOrderAdapter.MissingOrderState.empty());

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

	private ElevenstOrderSyncService service() {
		return new ElevenstOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository,
			productRepository, eventPublisher, elevenstOrderAdapter, syncStatusService, marketFeeService,
			Mockito.mock(TerminalSettlementService.class),
			Mockito
				.mock(MarketLineItemSyncDispatcher.class),
			shipmentRepository, Mockito
				.mock(MarketRegistrationLookup.class));
	}

	private void stubCredentialAndEmptyApi() {
		MarketCredential credential = Mockito.mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(Optional.of(credential));
		when(elevenstOrderAdapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(MarketFetchOutcome.complete(List.of()));
	}

	private Order order(String orderNo) {
		return Order.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo(orderNo)
			.orderDate(LocalDateTime.now().minusDays(1))
			.build();
	}

	private ElevenstOrderAdapter.MissingOrderState missingState(
		ShippingStatus status) {
		return new ElevenstOrderAdapter.MissingOrderState(
			Map.of(
				ElevenstOrderAdapter.CLAIM_ORDER_WIDE, status),
			Map.of());
	}

	private OrderLineItem item(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}
}
