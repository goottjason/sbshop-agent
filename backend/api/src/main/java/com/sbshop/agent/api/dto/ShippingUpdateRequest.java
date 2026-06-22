package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import lombok.Data;

@Data
public class ShippingUpdateRequest {
	private String trackingNo;
	private ShippingCarrier shippingCarrier;

	public ShippingUpdateCommand toCommand() {
		return ShippingUpdateCommand.builder()
			.trackingNo(this.trackingNo)
			.shippingCarrier(this.shippingCarrier)
			.build();
	}
}
