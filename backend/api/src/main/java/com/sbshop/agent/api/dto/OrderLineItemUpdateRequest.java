package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderLineItemUpdateRequest {
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
	private Boolean marketplaceSynced;
	private String marketProductCode;
	private BigDecimal settlementAmount;
}
