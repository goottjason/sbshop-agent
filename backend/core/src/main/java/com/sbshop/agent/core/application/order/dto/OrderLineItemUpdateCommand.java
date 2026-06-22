package com.sbshop.agent.core.application.order.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderLineItemUpdateCommand {
	private Boolean isUnipassDone;
}
