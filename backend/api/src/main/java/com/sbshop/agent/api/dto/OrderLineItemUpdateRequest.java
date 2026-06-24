package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand;

import lombok.Data;

@Data
public class OrderLineItemUpdateRequest {
	private Boolean isUnipassDone;

	public OrderLineItemUpdateCommand toCommand() {
		return OrderLineItemUpdateCommand.builder()
			.isUnipassDone(this.isUnipassDone)
			.build();
	}
}
