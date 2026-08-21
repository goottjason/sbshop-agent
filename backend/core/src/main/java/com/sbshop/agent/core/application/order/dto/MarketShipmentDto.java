package com.sbshop.agent.core.application.order.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class MarketShipmentDto {
	private String marketShipmentNo;

	private String trackingNo;
	private ShippingCarrier carrier;

	private String deliveryStatus;

	private LocalDateTime shippedAt;

	@Builder.Default
	private List<MarketLineItemDto> lineItems = new ArrayList<>();
}
