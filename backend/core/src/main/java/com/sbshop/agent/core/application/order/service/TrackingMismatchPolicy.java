package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;

public enum TrackingMismatchPolicy {
	NONE,
	AUTO_RESEND,
	MANUAL_FIX;

	public static TrackingMismatchPolicy of(MarketType marketType, Shipment shipment) {
		if (shipment == null || shipment.getMarketTrackingNo() == null || !shipment.isMarketOutOfSync()) {
			return NONE;
		}
		return marketType == MarketType.COUPANG ? AUTO_RESEND : MANUAL_FIX;
	}
}
