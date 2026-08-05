package com.sbshop.agent.core.application.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 어댑터가 마켓 응답을 (주문 / 배송 / 상품주문) 3계층으로 정규화해 넘기기 위한 DTO.
 * 상위 서비스는 이 구조만 보고 마켓을 모른다.
 *
 * <p>정산예정금액을 상품주문에 둔 것은, 11번가 {@code stlPlnAmt}·N스토어
 * {@code expectedSettlementAmount}가 상품주문별로 오기 때문이다. 요율을 곱해 추정하고
 * 분배하는 대신 실측값을 그대로 담을 자리를 미리 만들어 둔다(도입 자체는 별도 항목).
 */
class MarketShipmentDtoTest {

	@Test
	@DisplayName("배송 하나에 상품주문 여러 건을 담는다 — 묶음배송의 표현")
	void holdsMultipleLineItems() {
		MarketShipmentDto shipment = MarketShipmentDto.builder()
			.marketShipmentNo("2716448228")
			.trackingNo("424079080471")
			.carrier(ShippingCarrier.CJ_LOGISTICS)
			.lineItems(List.of(
				MarketLineItemDto.builder()
					.marketLineItemNo("1")
					.productName("쏜리서치 Calcium Magnesium")
					.quantity(1)
					.totalAmount(new BigDecimal("57700"))
					.status(ShippingStatus.NEW)
					.build(),
				MarketLineItemDto.builder()
					.marketLineItemNo("2")
					.productName("쏜리서치 베이직 뉴트리언트")
					.quantity(1)
					.totalAmount(new BigDecimal("52800"))
					.status(ShippingStatus.SHIPPED)
					.build()))
			.build();

		assertThat(shipment.getLineItems()).hasSize(2);
		// 같은 배송인데 상품주문마다 상태가 갈린다 — 상태를 라인아이템에 두는 이유다.
		assertThat(shipment.getLineItems())
			.extracting(MarketLineItemDto::getStatus)
			.containsExactly(ShippingStatus.NEW, ShippingStatus.SHIPPED);
		assertThat(shipment.getLineItems())
			.extracting(MarketLineItemDto::getMarketLineItemNo)
			.containsExactly("1", "2");
	}

	@Test
	@DisplayName("lineItems를 안 주면 빈 목록이다 — null 방어 없이 순회할 수 있어야 한다")
	void defaultsToEmptyLineItems() {
		MarketShipmentDto shipment = MarketShipmentDto.builder()
			.marketShipmentNo("D1")
			.build();

		assertThat(shipment.getLineItems()).isEmpty();
	}

	@Test
	@DisplayName("주문 DTO는 배송 목록을 담을 수 있고, 안 담으면 null이다(평면 DTO 표시)")
	void orderDtoCarriesShipments() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.build();
		assertThat(flat.getShipments()).isNull();

		MarketOrderDto nested = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.shipments(List.of(MarketShipmentDto.builder().marketShipmentNo("D1").build()))
			.build();
		assertThat(nested.getShipments()).hasSize(1);
	}
}
