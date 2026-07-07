package com.sbshop.agent.core.application.order.dto;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShippingUpdateCommand {
	private String trackingNo;
	private ShippingCarrier shippingCarrier;
	private ShippingStatus shippingStatus;
	private Boolean trackingSentToMarket;

	public ShippingData toShippingData(ShippingData existing) {
		ShippingData.ShippingDataBuilder builder = existing != null ? existing.toBuilder() : ShippingData.builder();
		if (this.trackingNo != null)
			builder.trackingNo(this.trackingNo);
		if (this.shippingCarrier != null)
			builder.shippingCarrier(this.shippingCarrier);
		if (this.shippingStatus != null)
			builder.shippingStatus(this.shippingStatus);
		if (this.trackingSentToMarket != null)
			builder.trackingSentToMarket(this.trackingSentToMarket);
		return builder.build();
	}
}
