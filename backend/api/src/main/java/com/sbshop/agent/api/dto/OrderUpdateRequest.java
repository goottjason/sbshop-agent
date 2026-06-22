package com.sbshop.agent.api.dto;

import lombok.Data;

@Data
public class OrderUpdateRequest {
	private String address;
	private String customsClearanceNo;
}
