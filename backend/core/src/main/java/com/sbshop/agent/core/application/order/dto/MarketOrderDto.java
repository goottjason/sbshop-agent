package com.sbshop.agent.core.application.order.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder(toBuilder = true)
public class MarketOrderDto {
	private MarketType marketType;
	private String marketOrderNo;
	private String marketProductCode;
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

	private Map<String, Object> marketSpecificData;

	private List<MarketShipmentDto> shipments;
}
