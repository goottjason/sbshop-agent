package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import lombok.Data;

@Data
public class OrderUpdateRequest {
	private String address;
	private String customsClearanceNo;
	private String message;

	public OrderUpdateCommand toCommand() {
		return OrderUpdateCommand.builder()
			.address(this.address)
			.customsClearanceNo(this.customsClearanceNo)
			.message(this.message)
			.build();
	}
}
