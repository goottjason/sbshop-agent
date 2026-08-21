package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreDispatchKeyTest {
	@Mock
	private SmartStoreOrderApiPort apiPort;
	@Mock
	private SmartStoreStatusMapper statusMapper;

	private SmartStoreOrderAdapter adapter;
	private MarketCredential credential;

	@BeforeEach
	void setUp() {
		adapter = new SmartStoreOrderAdapter(apiPort, statusMapper);
		credential = mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("clientId");
		when(credential.getSecretKey()).thenReturn("secret");
	}

	@Test
	@DisplayName("발송처리는 라인아이템의 상품주문번호를 쓴다 — 주문번호(orderId)를 보내지 않는다")
	void shipOrder_usesLineItemProductOrderId() {
		Order order = orderWithProductOrderIds("PO-1", "PO-2");

		adapter.shipOrder(credential, order, lineItem("PO-2"), "123456789012", ShippingCarrier.CJ_LOGISTICS);

		verify(apiPort).shipOrder(eq("clientId"), eq("secret"), eq("PO-2"),
			eq("123456789012"), eq("CJGLS"));
		verify(apiPort, never()).shipOrder(any(), any(), eq("2026072134143761"), any(), any());
	}

	@Test
	@DisplayName("라인아이템에 식별자가 없어도 주문의 상품주문이 하나뿐이면 그것으로 발송처리한다")
	void shipOrder_fallsBackToSoleProductOrderId() {
		Order order = orderWithProductOrderIds("PO-1");

		adapter.shipOrder(credential, order, lineItem(null), "123456789012", ShippingCarrier.CJ_LOGISTICS);

		verify(apiPort).shipOrder(eq("clientId"), eq("secret"), eq("PO-1"),
			eq("123456789012"), eq("CJGLS"));
	}

	@Test
	@DisplayName("상품주문번호를 특정할 수 없으면 발송하지 않고 즉시 알린다(엉뚱한 상품 발송 방지)")
	void shipOrder_failsFastWhenAmbiguous() {
		Order order = orderWithProductOrderIds("PO-1", "PO-2");

		assertThatThrownBy(() -> adapter.shipOrder(
			credential, order, lineItem(null), "123456789012", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("상품주문번호");

		verify(apiPort, never()).shipOrder(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("전환 전 저장된 주문은 주문번호가 곧 상품주문번호다 — 그 경우에만 주문번호로 발송한다")
	void legacyOrder_shipsWithMarketOrderNo() {
		Order legacy = Order.builder()
			.marketType(MarketType.SMART_STORE)
			.marketOrderNo("2026072251442781")
			.build();

		adapter.shipOrder(credential, legacy, lineItem(null), "123456789012", ShippingCarrier.CJ_LOGISTICS);

		verify(apiPort).shipOrder(eq("clientId"), eq("secret"), eq("2026072251442781"),
			eq("123456789012"), eq("CJGLS"));
	}

	@Test
	@DisplayName("발주확인은 주문의 모든 상품주문을 확인한다 — 하나라도 남으면 마켓이 발송준비로 넘기지 않는다")
	void acceptOrders_confirmsAllProductOrders() {
		adapter.acceptOrders(credential, orderWithProductOrderIds("PO-1", "PO-2"));

		@SuppressWarnings("unchecked") ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		verify(apiPort).confirmOrders(eq("clientId"), eq("secret"), captor.capture());
		assertThat(captor.getValue()).containsExactly("PO-1", "PO-2");
	}

	@Test
	@DisplayName("주문취소도 모든 상품주문을 대상으로 한다")
	void cancelOrder_cancelsAllProductOrders() {
		adapter.cancelOrder(credential, orderWithProductOrderIds("PO-1", "PO-2"));

		@SuppressWarnings("unchecked") ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		verify(apiPort).cancelOrders(eq("clientId"), eq("secret"), captor.capture());
		assertThat(captor.getValue()).containsExactly("PO-1", "PO-2");
	}

	@Test
	@DisplayName("상품주문번호를 모르는 레거시 주문은 주문번호로 폴백한다(전환 전 저장분)")
	void legacyOrderWithoutProductOrderIds_fallsBackToMarketOrderNo() {
		Order legacy = Order.builder()
			.marketType(MarketType.SMART_STORE)
			.marketOrderNo("2026072251442781")
			.build();

		adapter.acceptOrders(credential, legacy);

		@SuppressWarnings("unchecked") ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		verify(apiPort).confirmOrders(eq("clientId"), eq("secret"), captor.capture());
		assertThat(captor.getValue()).containsExactly("2026072251442781");
	}

	private Order orderWithProductOrderIds(String... productOrderIds) {
		Order order = Order.builder()
			.marketType(MarketType.SMART_STORE)
			.marketOrderNo("2026072134143761")
			.build();
		order.setMarketSpecificDataFromMap(
			Map.of("productOrderIds", String.join("|", productOrderIds)));
		return order;
	}

	private OrderLineItem lineItem(String marketLineItemNo) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.marketLineItemNo(marketLineItemNo)
			.build();
	}
}
