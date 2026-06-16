package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class OrderLineItemUpdateCommand {
	private String sourcingAccount;
	private String sourcingOrderNo;
	private BigDecimal sourcingAmount;
	private String discountCode;
	private String sourcingVendor;
	private BigDecimal shippingFee;
	private ShippingCarrier shippingCarrier;
	private String trackingNo;
	private ShippingStatus shippingStatus;
	private Boolean isUnipassDone;
	private Boolean trackingSentToMarket;
	private BigDecimal settlementAmount;
}
