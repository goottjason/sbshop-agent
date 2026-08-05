package com.sbshop.agent.core.application.order.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * 마켓 상품주문 1건 (11번가 ordPrdSeq · N스토어 productOrderId ·
 * 쿠팡 orderItems 원소 · Cafe24 items 원소).
 *
 * <p>진행상태({@code status})가 여기 있는 이유: 같은 주문·같은 배송지라도 상품마다 상태가
 * 갈린다. 11번가 20260731088778989는 순번 1이 결제완료, 순번 2가 발송완료였다.
 */
@Getter
@Setter
@Builder
public class MarketLineItemDto {

	/** 마켓 상품주문 식별자 — 동기화 매칭 키 */
	private String marketLineItemNo;

	private String marketProductCode;
	private String sellerProductId;
	private String productName;
	private Integer quantity;
	private BigDecimal orderPrice;
	private BigDecimal totalAmount;

	/**
	 * 마켓이 알려준 정산예정금액 (11번가 stlPlnAmt · N스토어 expectedSettlementAmount).
	 * 상품주문별로 오므로 분배가 필요 없다. 도입 전에는 null이며, 그때는 요율 추정을 쓴다.
	 */
	private BigDecimal settlementAmount;

	/** 이 상품주문의 진행상태 */
	private ShippingStatus status;

	/** 마켓별 부가 데이터 (11번가 ordPrdSeq·addPrdYn 등) */
	private Map<String, Object> marketSpecificData;
}
