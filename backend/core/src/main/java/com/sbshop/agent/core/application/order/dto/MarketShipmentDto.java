package com.sbshop.agent.core.application.order.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * 하나의 배송 — 송장 1개가 곧 이것 하나다.
 *
 * <p>{@code marketShipmentNo}에는 마켓별 배송 식별자가 들어간다:
 * 11번가 {@code dlvNo} · 쿠팡 {@code shipmentBoxId} · N스토어 {@code packageNumber} ·
 * Cafe24 shipment 식별자. 얻을 수 없으면 상품주문번호로 대체한다(배송 1 : 상품주문 1).
 * 배송이 없는 주문은 만들지 않는다 — 상위 로직에 분기가 생기기 때문이다.
 */
@Getter
@Setter
@Builder
public class MarketShipmentDto {

	/** 마켓 배송 식별자 — 동기화 매칭 키이자 발송처리 API의 호출 단위 */
	private String marketShipmentNo;

	private String trackingNo;
	private ShippingCarrier carrier;

	/** 마켓이 주는 배송 자체의 상태. 코드계가 마켓마다 달라 원문 문자열로 둔다. */
	private String deliveryStatus;

	private LocalDateTime shippedAt;

	@Builder.Default
	private List<MarketLineItemDto> lineItems = new ArrayList<>();
}
