package com.sbshop.agent.core.domain.order.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourcingData {
	@Column(name = "sourcing_account", length = 100)
	private String sourcingAccount;

	@Column(name = "sourcing_order_no", length = 100)
	private String sourcingOrderNo;

	@Column(name = "sourcing_amount", precision = 10, scale = 2)
	private BigDecimal sourcingAmount;

	@Column(name = "discount_code", length = 50)
	private String discountCode;

	@Column(name = "sourcing_vendor", length = 50)
	private String sourcingVendor;

	@Builder(toBuilder = true)
	public SourcingData(
		String sourcingAccount,
		String sourcingOrderNo,
		BigDecimal sourcingAmount,
		String discountCode,
		String sourcingVendor) {
		this.sourcingAccount = sourcingAccount;
		this.sourcingOrderNo = sourcingOrderNo;
		this.sourcingAmount = sourcingAmount;
		this.discountCode = discountCode;
		this.sourcingVendor = sourcingVendor;
	}
}
