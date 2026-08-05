package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 어댑터를 마켓별로 순차 전환하는 동안 평면 DTO와 3계층 DTO가 공존한다.
 * 소비자가 분기를 갖지 않도록, 정규화기가 평면 DTO를 배송 1 : 상품주문 1로 감싼다.
 *
 * <p>이 래핑이 1단계 "동작 불변"의 핵심이다 — 단일 상품 주문(현재 데이터의 전부)이
 * 3계층을 거쳐도 같은 결과가 나와야 한다.
 */
class MarketOrderNormalizerTest {

	@Test
	@DisplayName("평면 DTO는 배송 1 : 상품주문 1로 감싸지고 값이 그대로 옮겨진다")
	void wrapsFlatDtoIntoSingleShipmentAndLineItem() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo("20260731088778989")
			.marketProductCode("210121IHB011")
			.productName("쏜리서치 Calcium Magnesium")
			.quantity(1)
			.orderPrice(new BigDecimal("57700"))
			.totalAmount(new BigDecimal("57700"))
			.status(ShippingStatus.NEW)
			.trackingNo("424079080471")
			.carrier(ShippingCarrier.CJ_LOGISTICS)
			.marketSpecificData(Map.of("ordPrdSeq", "1"))
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getShipments()).hasSize(1);
		MarketShipmentDto shipment = result.getShipments().get(0);
		assertThat(shipment.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipment.getCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);

		assertThat(shipment.getLineItems()).hasSize(1);
		MarketLineItemDto item = shipment.getLineItems().get(0);
		assertThat(item.getMarketProductCode()).isEqualTo("210121IHB011");
		assertThat(item.getProductName()).isEqualTo("쏜리서치 Calcium Magnesium");
		assertThat(item.getQuantity()).isEqualTo(1);
		assertThat(item.getOrderPrice()).isEqualByComparingTo("57700");
		assertThat(item.getTotalAmount()).isEqualByComparingTo("57700");
		assertThat(item.getStatus()).isEqualTo(ShippingStatus.NEW);
		assertThat(item.getMarketSpecificData()).containsEntry("ordPrdSeq", "1");
	}

	@Test
	@DisplayName("배송 식별자가 없으면 주문번호로 대체한다 — 배송 없는 주문은 만들지 않는다")
	void fallsBackToOrderNoAsShipmentNo() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getShipments().get(0).getMarketShipmentNo())
			.isEqualTo("20260731088778989");
		assertThat(result.getShipments().get(0).getLineItems().get(0).getMarketLineItemNo())
			.isEqualTo("20260731088778989");
	}

	@Test
	@DisplayName("쿠팡처럼 shipmentBoxId가 있으면 그것을 배송 식별자로 쓴다")
	void usesShipmentBoxIdWhenPresent() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("3000012345")
			.shipmentBoxId("77001122")
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getShipments().get(0).getMarketShipmentNo()).isEqualTo("77001122");
	}

	@Test
	@DisplayName("이미 3계층인 DTO는 그대로 돌려준다")
	void passesThroughAlreadyNestedDto() {
		MarketShipmentDto given = MarketShipmentDto.builder()
			.marketShipmentNo("D1")
			.lineItems(List.of(MarketLineItemDto.builder().marketLineItemNo("1").build()))
			.build();
		MarketOrderDto nested = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.shipments(List.of(given))
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(nested);

		assertThat(result.getShipments()).containsExactly(given);
	}

	@Test
	@DisplayName("원본 DTO를 건드리지 않는다")
	void doesNotMutateInput() {
		MarketOrderDto flat = MarketOrderDto.builder().marketOrderNo("A1").build();

		MarketOrderNormalizer.normalize(flat);

		assertThat(flat.getShipments()).isNull();
	}

	@Test
	@DisplayName("주문 공통 필드(수취인·주소·통관번호)는 주문 계층에 남는다")
	void keepsOrderLevelFields() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("A1")
			.recipientName("정나영")
			.address("서울특별시 양천구")
			.customsClearanceNo("P200032008307")
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getRecipientName()).isEqualTo("정나영");
		assertThat(result.getAddress()).isEqualTo("서울특별시 양천구");
		assertThat(result.getCustomsClearanceNo()).isEqualTo("P200032008307");
	}
}
