package com.sbshop.agent.core.application.order.probe;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

public record OrderProbeResult(OrderProbeStatus status, ShippingStatus shippingStatus, String rawMessage) {

	public static OrderProbeResult found(ShippingStatus shippingStatus) {
		return new OrderProbeResult(OrderProbeStatus.FOUND, shippingStatus, null);
	}

	public static OrderProbeResult terminated(ShippingStatus shippingStatus, String rawMessage) {
		return new OrderProbeResult(OrderProbeStatus.TERMINATED, shippingStatus, rawMessage);
	}

	public static OrderProbeResult notFound(String rawMessage) {
		return new OrderProbeResult(OrderProbeStatus.NOT_FOUND, null, rawMessage);
	}

	public static OrderProbeResult unknown(String rawMessage) {
		return new OrderProbeResult(OrderProbeStatus.UNKNOWN, null, rawMessage);
	}
}
