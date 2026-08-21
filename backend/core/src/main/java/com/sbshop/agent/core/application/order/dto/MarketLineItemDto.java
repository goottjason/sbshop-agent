package com.sbshop.agent.core.application.order.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class MarketLineItemDto {
	private String marketLineItemNo;

	private String marketProductCode;

	private String sellerProductId;

	private String productName;
	private Integer quantity;
	private BigDecimal orderPrice;
	private BigDecimal totalAmount;

	private BigDecimal settlementAmount;

	private ShippingStatus status;

	private Map<String, Object> marketSpecificData;
}
