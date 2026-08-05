package com.sbshop.agent.core.application.order.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * 마켓별 주문 데이터를 통합된 형태로 표현하는 DTO
 * 각 마켓 어댑터에서 API 응답을 이 DTO로 변환하여 반환
 */
@Getter
@Setter
@Builder(toBuilder = true)
public class MarketOrderDto {
	private MarketType marketType;
	private String marketOrderNo;
	private String marketProductCode;
	/**
	 * (D-046) 쿠팡 발행 시 marketIdentifiers에 저장되는 식별자(sellerProductId).
	 * 주문 매칭 키(marketProductCode=vendorItemId)가 저장 식별자와 달라 매칭이 끊기던 결함을
	 * 역조회·보강으로 잇기 위해 함께 전달한다. 쿠팡 외 마켓은 미사용(null).
	 */
	private String sellerProductId;
	private String productName;
	private String sellerProductName;
	private Integer quantity;
	private BigDecimal orderPrice;
	private BigDecimal totalAmount;

	private String recipientName;
	private String recipientPhone;
	private String zipcode;
	private String address;
	private String message;

	private String ordererName;
	private String ordererPhone;

	private String customsClearanceNo;

	private String trackingNo;
	private ShippingCarrier carrier;

	private ShippingStatus status;
	private LocalDateTime orderDate;

	private String shipmentBoxId;

	private Map<String, Object> marketSpecificData;

	/**
	 * 3계층으로 정규화된 배송 목록 (묶음배송·다품목 주문의 표현).
	 *
	 * <p>{@code null}이면 아직 평면 DTO라는 뜻이다 — {@code MarketOrderNormalizer}가
	 * 배송 1 : 상품주문 1로 감싼다. 어댑터가 마켓별로 순차 전환되는 동안 두 형태가
	 * 공존하므로, 소비자는 정규화기를 거친 값만 본다.
	 */
	private java.util.List<MarketShipmentDto> shipments;
}
