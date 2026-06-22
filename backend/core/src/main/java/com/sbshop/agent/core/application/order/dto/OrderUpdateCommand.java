package com.sbshop.agent.core.application.order.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderUpdateCommand {
	private String address;
	private String customsClearanceNo;
}
