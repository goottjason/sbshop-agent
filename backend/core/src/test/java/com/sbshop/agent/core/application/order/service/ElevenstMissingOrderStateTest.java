package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter.MissingOrderState;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-157/D-158: 진행상태 목록에서 사라진 11번가 주문의 <b>종결 상태와 마켓 보유 송장</b>을 반영한다.
 *
 * <p>신고(2026-08-08): 주문 {@code 20260720086485068}(엄수현)은 마켓에서 <b>구매확정</b>이고 마켓 송장은
 * 우체국 {@code 6079990333504}인데, 시스템은 {@code SHIPPED} + 마켓 송장 미수집이었다.
 * 원인은 매핑 부재가 아니라 <b>경로 부재</b>다 — {@code applyProductOrderStatuses}는 4개 목록에서 수집된
 * 주문만 보고, 이를 보완하는 사라진-주문 조회는 클레임(취소·반품·교환)만 반영하고 나머지를 버렸다.
 *
 * <p>마켓 송장을 수집하지 못하면 화면 배지가 두 송장을 비교할 수 없어 판정 근거를 잃는다(D-156과 맞물림).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstMissingOrderStateTest {

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
	private ShipmentRepository shipmentRepository;

	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

	private ElevenstOrderSyncService service() {
		return new ElevenstOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository,
			productRepository, eventPublisher, elevenstOrderAdapter, syncStatusService, marketFeeService,
			mock(TerminalSettlementService.class), mock(MarketLineItemSyncDispatcher.class),
			shipmentRepository, org.mockito.Mockito
				.mock(com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository.class));
	}

	private void stubCredentialAndEmptyApi() {
		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(java.util.Optional.of(credential));
		when(elevenstOrderAdapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(com.sbshop.agent.core.application.order.dto.MarketFetchOutcome.complete(List.of()));
	}

	private Order order() {
		Order o = Order.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo("20260720086485068")
			.orderDate(LocalDateTime.now().minusDays(3))
			.build();
		return o;
	}

	private OrderLineItem item(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	private Shipment shipment(String ourTracking) {
		return Shipment.builder()
			.orderId(1L)
			.marketShipmentNo("2714461728")
			.trackingNo(ourTracking)
			.build();
	}

	private void stubOrderWith(OrderLineItem li, MissingOrderState state) {
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order()));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));
		when(elevenstOrderAdapter.resolveMissingOrderState(any(), any())).thenReturn(state);
	}

	@Test
	@DisplayName("D-157: 사라진 주문이 구매확정이면 DELIVERED로 반영된다 — 배송중으로 굳지 않는다")
	void purchaseConfirmedBecomesDelivered() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		stubOrderWith(li, new MissingOrderState(
			Map.of(ElevenstOrderAdapter.CLAIM_ORDER_WIDE, ShippingStatus.DELIVERED), Map.of()));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("D-158: 사라진 주문의 마켓 보유 송장이 배송 계층에 기록된다")
	void marketTrackingIsRecorded() {
		stubCredentialAndEmptyApi();
		Shipment shipment = shipment("424438293101"); // 우리 송장(CJ)
		when(shipmentRepository.findByOrderId(any())).thenReturn(List.of(shipment));
		stubOrderWith(item(ShippingStatus.SHIPPED), new MissingOrderState(
			Map.of(ElevenstOrderAdapter.CLAIM_ORDER_WIDE, ShippingStatus.DELIVERED),
			Map.of("1", "6079990333504"))); // 마켓 송장(우체국)

		service().syncElevenstOrders();

		assertThat(shipment.getMarketTrackingNo()).isEqualTo("6079990333504");
		// 우리 송장은 마켓 값으로 덮이지 않는다 — 진실은 발송처(iHerb 메일)다(D-148).
		assertThat(shipment.getTrackingNo()).isEqualTo("424438293101");
	}

	@Test
	@DisplayName("마켓 값이 우리 송장과 같아지면 수동수정 표시가 스스로 꺼진다")
	void manualFixClearsWhenMarketMatches() {
		stubCredentialAndEmptyApi();
		Shipment shipment = shipment("424438293101");
		shipment.markManualFixRequired();
		when(shipmentRepository.findByOrderId(any())).thenReturn(List.of(shipment));
		stubOrderWith(item(ShippingStatus.SHIPPED), new MissingOrderState(
			Map.of(), Map.of("1", "424438293101"))); // 사람이 판매자센터에서 고친 상태

		service().syncElevenstOrders();

		assertThat(shipment.isManualFixRequired()).isFalse();
	}

	@Test
	@DisplayName("순번별 마켓 송장이 서로 다르면 기록하지 않는다 — 어느 배송의 것인지 근거가 없다")
	void skipsWhenTrackingNosDisagree() {
		stubCredentialAndEmptyApi();
		Shipment shipment = shipment("424438293101");
		when(shipmentRepository.findByOrderId(any())).thenReturn(List.of(shipment));
		stubOrderWith(item(ShippingStatus.SHIPPED), new MissingOrderState(
			Map.of(), Map.of("1", "6079990333504", "2", "111122223333")));

		service().syncElevenstOrders();

		assertThat(shipment.getMarketTrackingNo()).isNull();
	}

	@Test
	@DisplayName("종결 상태가 아니면 상태를 바꾸지 않는다 — 사라진 주문에 진행 상태를 되씌우지 않는다")
	void nonTerminalStatusLeavesShippingUntouched() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		// 어댑터가 종결이 아닌 상태를 담지 않으므로 statuses는 비어 있다(계약).
		stubOrderWith(li, new MissingOrderState(Map.of(), Map.of("1", "6079990333504")));
		when(shipmentRepository.findByOrderId(any())).thenReturn(List.of(shipment("424438293101")));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
	}
}
