package com.sbshop.agent.core.application.order.dto;

import com.sbshop.agent.core.domain.order.vo.CustomsData;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderUpdateCommand {
	private String address;
	private String customsClearanceNo;

	public CustomsData toCustomsData(CustomsData existing) {
		CustomsData.CustomsDataBuilder builder =
			existing != null ? existing.toBuilder() : CustomsData.builder();
		if (this.customsClearanceNo != null) builder.customsClearanceNo(this.customsClearanceNo);
		return builder.build();
	}
}
