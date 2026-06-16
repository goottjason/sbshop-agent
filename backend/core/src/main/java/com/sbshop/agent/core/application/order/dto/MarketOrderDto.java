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
@Builder
public class MarketOrderDto {
	private MarketType marketType;
	private String marketOrderNo;
	private String marketProductCode;
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
}
