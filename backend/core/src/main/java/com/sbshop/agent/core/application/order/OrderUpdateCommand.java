package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderUpdateCommand {
	private String recipientName;
	private String recipientPhone;
	private String zipcode;
	private String address;
	private String message;
	private String customsClearanceNo;
	private CustomsStatus customsStatus;
}
