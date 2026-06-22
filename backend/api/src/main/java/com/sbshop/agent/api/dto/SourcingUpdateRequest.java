package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.application.order.dto.SourcingUpdateCommand;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class SourcingUpdateRequest {
	private String sourcingAccount;
	private String sourcingOrderNo;
	private BigDecimal sourcingAmount;
	private BigDecimal logisticsCost;
	private String discountCode;
	private String sourcingVendor;

	public SourcingUpdateCommand toCommand() {
		return SourcingUpdateCommand.builder()
			.sourcingAccount(this.sourcingAccount)
			.sourcingOrderNo(this.sourcingOrderNo)
			.sourcingAmount(this.sourcingAmount)
			.logisticsCost(this.logisticsCost)
			.discountCode(this.discountCode)
			.sourcingVendor(this.sourcingVendor)
			.build();
	}
}
