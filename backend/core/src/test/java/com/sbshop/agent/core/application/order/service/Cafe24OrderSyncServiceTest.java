package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Cafe24 주문 API → G마켓/옥션 주문 매핑/저장 검증.
 * order_place_id로 gmarket/auction만 처리하고(타마켓 스킵), 필드를 도메인에 정확히 채우는지.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24OrderSyncServiceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock private Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private ApplicationEventPublisher eventPublisher;

	private Cafe24OrderSyncService service;

	@BeforeEach
	void setUp() {
		service = new Cafe24OrderSyncService(cafe24OrderApiPort, orderRepository,
			orderLineItemRepository, marketRegistrationRepository, eventPublisher);
		lenient().when(marketRegistrationRepository.findByMarketTypeAndIdentifiersContaining(
			org.mockito.ArgumentMatchers.any(), anyString())).thenReturn(List.of());
	}

	private JsonNode ordersJson() throws Exception {
		String json = """
			{"orders":[
			  {"order_id":"20240711-0000001","order_place_id":"gmarket","order_place_name":"지마켓",
			   "order_date":"2024-07-11T12:00:00+09:00","market_order_no":"GM123",
			   "buyer":{"name":"홍길동","cellphone":"010-1111-2222"},
			   "receivers":[{"name":"김수취","cellphone":"010-3333-4444","zipcode":"12345",
			      "address_full":"서울시 강남구 테헤란로","shipping_message":"문앞"}],
			   "items":[{"product_no":"7034","product_code":"P7034","product_name":"테스트상품",
			      "quantity":2,"payment_amount":"10000","order_status":"N10","tracking_no":""}]},
			  {"order_id":"CP-999","order_place_id":"coupang","order_date":"2024-07-11T12:00:00+09:00",
			   "buyer":{},"receivers":[],"items":[]}
			]}
			""";
		return MAPPER.readTree(json).path("orders");
	}

	@Test
	@DisplayName("gmarket 주문은 GMARKET으로 저장하고, coupang 등 타마켓은 스킵한다")
	void mapsGmarketAndSkipsOthers() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0))).thenReturn(ordersJson());
		when(orderRepository.findByMarketOrderNo("20240711-0000001")).thenReturn(Optional.empty());

		int processed = service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		assertThat(processed).isEqualTo(1); // gmarket 1건만, coupang 스킵
		// coupang 주문은 조회조차 하지 않음
		verify(orderRepository, never()).findByMarketOrderNo("CP-999");

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());
		Order saved = orderCaptor.getValue();
		assertThat(saved.getMarketType()).isEqualTo(MarketType.GMARKET);
		assertThat(saved.getMarketOrderNo()).isEqualTo("20240711-0000001");
		assertThat(saved.getRecipientName()).isEqualTo("김수취");
		assertThat(saved.getRecipientPhone()).isEqualTo("010-3333-4444");
		assertThat(saved.getAddress()).isEqualTo("서울시 강남구 테헤란로");
		assertThat(saved.getOrdererName()).isEqualTo("홍길동");

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, times(1)).save(itemCaptor.capture());
		OrderLineItem item = itemCaptor.getValue();
		assertThat(item.getQuantity()).isEqualTo(2);
		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW); // N10 → NEW
	}
}
