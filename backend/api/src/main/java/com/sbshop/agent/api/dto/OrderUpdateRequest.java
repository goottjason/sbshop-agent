package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import lombok.Data;

@Data
public class OrderUpdateRequest {
	private String recipientName;
	private String recipientPhone;
	private String zipcode;
	private String address;
	private String message;
	private String customsClearanceNo;
	private CustomsStatus customsStatus;
}
