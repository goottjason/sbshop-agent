package com.sbshop.agent.core.application.order.dto;

import java.math.BigDecimal;

import com.sbshop.agent.core.domain.order.vo.SourcingData;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SourcingUpdateCommand {
	private String sourcingAccount;
	private String sourcingOrderNo;
	private BigDecimal sourcingAmount;
	private BigDecimal logisticsCost;
	private String discountCode;
	private String sourcingVendor;

	public SourcingData toSourcingData(SourcingData existing) {
		SourcingData.SourcingDataBuilder builder =
			existing != null ? existing.toBuilder() : SourcingData.builder();
		if (this.sourcingVendor != null) builder.sourcingVendor(this.sourcingVendor);
		if (this.sourcingAccount != null) builder.sourcingAccount(this.sourcingAccount);
		if (this.sourcingOrderNo != null) builder.sourcingOrderNo(this.sourcingOrderNo);
		if (this.sourcingAmount != null) builder.sourcingAmount(this.sourcingAmount);
		if (this.logisticsCost != null) builder.logisticsCost(this.logisticsCost);
		if (this.discountCode != null) builder.discountCode(this.discountCode);
		return builder.build();
	}
}
