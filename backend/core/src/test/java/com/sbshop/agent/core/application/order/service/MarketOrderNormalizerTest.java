package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.util.HashMap;
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
	}

	@Test
	@DisplayName("상품주문 식별자는 위조하지 않는다 — 전환 전 마켓은 null(=아직 모름)")
	void doesNotFabricateLineItemNo() {
		// D-131: 종전에는 배송 식별자(=주문번호/shipmentBoxId)를 상품주문 식별자 자리에도 넣었다.
		// market_line_item_no는 "마켓 상품주문번호"를 뜻하므로 주문번호를 넣는 것은 거짓이고,
		// uk_line_item_order_market_no 하에서 주문당 라인아이템이 2건이 되는 순간 유니크 위반으로
		// 동기화가 통째로 실패한다. PostgreSQL은 NULL끼리 충돌로 보지 않으므로 null이 안전하고 정직하다.
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getShipments().get(0).getLineItems().get(0).getMarketLineItemNo())
			.isNull();
	}

	@Test
	@DisplayName("6단계: 평면 DTO의 배송 식별자는 주문번호다 — 배송박스번호를 주문 계층으로 나르지 않는다")
	void flatDtoUsesMarketOrderNoAsShipmentNo() {
		// 종전에는 쿠팡의 shipmentBoxId를 평면 DTO에서 받아 배송 식별자로 썼다. 쿠팡이 3계층으로
		// 전환된 뒤(D-137) 그 경로는 쓰이지 않게 됐고, 같은 값을 두 곳에서 나르는 것이 원본을 흐렸다.
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("3000012345")
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getShipments().get(0).getMarketShipmentNo()).isEqualTo("3000012345");
		// 배송 식별자를 상품주문 식별자 자리에 전용하지 않는다(D-131) — 주문당 2건이 되는 순간 충돌한다.
		assertThat(result.getShipments().get(0).getLineItems().get(0).getMarketLineItemNo())
			.isNull();
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

	@Test
	@DisplayName("marketSpecificData는 방어적으로 복사되어 원본과 독립적이다")
	void defensivelyCopiesMarketSpecificData() {
		Map<String, Object> mutableData = new HashMap<>();
		mutableData.put("ordPrdSeq", "1");
		mutableData.put("externalKey", "external-value");

		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.marketSpecificData(mutableData)
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);
		MarketLineItemDto lineItem = result.getShipments().get(0).getLineItems().get(0);

		// 반환된 라인아이템의 마켓 데이터를 수정
		lineItem.getMarketSpecificData().put("newKey", "new-value");
		lineItem.getMarketSpecificData().remove("externalKey");

		// 원본은 수정되지 않아야 한다
		assertThat(mutableData)
			.containsEntry("ordPrdSeq", "1")
			.containsEntry("externalKey", "external-value")
			.doesNotContainKey("newKey");
	}

	@Test
	@DisplayName("반환된 주문 DTO의 marketSpecificData도 방어적으로 복사되어 원본과 독립적이다")
	void defensivelyCopiesOrderLevelMarketSpecificData() {
		// 라인아이템만 방어 복사하고 주문 계층은 toBuilder()의 얕은 복사로 원본과 참조를
		// 공유하면, "정규화기는 방어 복사한다"는 믿음과 달리 2단계에서 어댑터가 채운
		// dlvNo·ordPrdSeq를 소비자가 변형할 때 원본이 오염된다.
		Map<String, Object> mutableData = new HashMap<>();
		mutableData.put("dlvNo", "D1");
		mutableData.put("externalKey", "external-value");

		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.marketSpecificData(mutableData)
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		// 반환된 주문 DTO의 마켓 데이터를 수정
		result.getMarketSpecificData().put("newKey", "new-value");
		result.getMarketSpecificData().remove("externalKey");

		// 원본은 수정되지 않아야 한다
		assertThat(mutableData)
			.containsEntry("dlvNo", "D1")
			.containsEntry("externalKey", "external-value")
			.doesNotContainKey("newKey");
	}
}
