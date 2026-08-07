package com.sbshop.agent.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

/**
 * SP-5 / F-ORD-7·16·24·28: order 쓰기 응답을 엔티티 → DTO로 바꾸되 JSON 계약을 보존한다.
 *
 * <p>보존 기준: 응답 DTO({@link OrderResponse}/{@link OrderLineItemResponse})를 Spring Boot 기본
 * ObjectMapper로 직렬화한 JSON이, 같은 상태의 엔티티를 직렬화한 JSON과 동일한 트리여야 한다.
 * 이 등가성이 깨지면 프론트 계약이 바뀐 것이므로 실패한다.
 */
class OrderResponseContractTest {

	/** Spring Boot 웹 계층 기본 매퍼를 복제(파생 getter·enum 문자열·null 포함 규칙 동일). */
	private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

	private Order sampleOrder() {
		return Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("ORD-1001")
			.orderDate(LocalDateTime.of(2026, 7, 14, 10, 30))
			.recipientName("홍길동")
			.recipientPhone("010-1111-2222")
			.zipcode("06236")
			.address("서울시 강남구 테헤란로 1")
			.message("문 앞에 놔주세요")
			.customsData(CustomsData.builder()
				.customsClearanceNo("P123456789012")
				.customsStatus(CustomsStatus.VALID)
				.verifiedPerson(VerifiedPerson.RECIPIENT)
				.build())
			.ordererName("김주문")
			.ordererPhone("010-3333-4444")
			// 6단계: shipmentBoxId는 주문에서 사라졌다 — 배송박스번호는 배송(sb_shipment)이 갖는다.
			.marketSpecificData("{\"cafe24_order_id\":\"CF-77\",\"ordPrdSeq\":\"1\"}")
			.build();
	}

	private OrderLineItem sampleLineItem() {
		return OrderLineItem.builder()
			.orderId(500L)
			.productId(700L)
			.quantity(3)
			.sourcingData(SourcingData.builder()
				.sourcingAccount("acc@iherb.com")
				.sourcingOrderNo("IHB-1")
				.sourcingAmount(new java.math.BigDecimal("12.34"))
				.logisticsCost(new java.math.BigDecimal("5.00"))
				.discountCode("SAVE10")
				.sourcingVendor("IHERB")
				.build())
			.settlementData(SettlementData.builder()
				.settlementAmount(new java.math.BigDecimal("20000.00"))
				.settlementVerified(true)
				.build())
			.shippingData(ShippingData.builder()
				.trackingNo("1234567890")
				.shippingStatus(ShippingStatus.SHIPPED)
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.trackingSentToMarket(true)
				.build())
			.isUnipassDone(true)
			.build();
	}

	@Test
	@DisplayName("OrderResponse.from(order) 직렬화 JSON은 Order 엔티티 직렬화 JSON과 동일하다")
	void orderResponsePreservesJsonContract() throws Exception {
		Order order = sampleOrder();

		JsonNode entityJson = mapper.valueToTree(order);
		JsonNode dtoJson = mapper.valueToTree(OrderResponse.from(order));

		assertThat(dtoJson).isEqualTo(entityJson);
	}

	@Test
	@DisplayName("OrderLineItemResponse.from(item) 직렬화 JSON은 OrderLineItem 엔티티 직렬화 JSON과 동일하다")
	void orderLineItemResponsePreservesJsonContract() throws Exception {
		OrderLineItem item = sampleLineItem();

		JsonNode entityJson = mapper.valueToTree(item);
		JsonNode dtoJson = mapper.valueToTree(OrderLineItemResponse.from(item));

		assertThat(dtoJson).isEqualTo(entityJson);
	}

	@Test
	@DisplayName("null 필드가 많은 최소 엔티티도 JSON 계약이 동일하게 보존된다")
	void preservesContractForMinimalEntities() throws Exception {
		Order minimalOrder = Order.builder()
			.marketType(MarketType.SMART_STORE)
			.marketOrderNo("ORD-MIN")
			.orderDate(LocalDateTime.of(2026, 1, 1, 0, 0))
			.build();
		OrderLineItem minimalItem = OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.build();

		JsonNode orderEntityJson = mapper.valueToTree(minimalOrder);
		JsonNode orderDtoJson = mapper.valueToTree(OrderResponse.from(minimalOrder));
		assertThat(orderDtoJson).isEqualTo(orderEntityJson);

		JsonNode itemEntityJson = mapper.valueToTree(minimalItem);
		JsonNode itemDtoJson = mapper.valueToTree(OrderLineItemResponse.from(minimalItem));
		assertThat(itemDtoJson).isEqualTo(itemEntityJson);
	}
}
