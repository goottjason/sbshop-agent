package com.sbshop.agent.core.application.order.probe;

import com.sbshop.agent.core.domain.order.enums.OrderProbeStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

/**
 * 마켓에 한 건을 물어본 답.
 *
 * <p>배송 단계와 클레임은 독립된 축이라 함께 실어 나른다(D-270). 마켓 송장도 같이 온다 —
 * 목록 조회가 닿지 않는 오래된 주문의 송장 대조가 여기 달려 있다(D-274).
 * 확인하지 못한 답({@code NOT_FOUND}/{@code UNKNOWN})은 아무 값도 싣지 않는다.
 */
public record OrderProbeResult(OrderProbeStatus status, ShippingStatus shippingStatus,
	ClaimData claim, String marketTrackingNo, String rawMessage) {

	public static OrderProbeResult found(ShippingStatus shippingStatus) {
		return found(shippingStatus, null, null);
	}

	public static OrderProbeResult found(ShippingStatus shippingStatus, ClaimData claim, String marketTrackingNo) {
		return new OrderProbeResult(OrderProbeStatus.FOUND, shippingStatus, claim, marketTrackingNo, null);
	}

	public static OrderProbeResult terminated(ShippingStatus shippingStatus, String rawMessage) {
		return terminated(shippingStatus, null, rawMessage);
	}

	public static OrderProbeResult terminated(ShippingStatus shippingStatus, ClaimData claim, String rawMessage) {
		return new OrderProbeResult(OrderProbeStatus.TERMINATED, shippingStatus, claim, null, rawMessage);
	}

	public static OrderProbeResult notFound(String rawMessage) {
		return new OrderProbeResult(OrderProbeStatus.NOT_FOUND, null, null, null, rawMessage);
	}

	public static OrderProbeResult unknown(String rawMessage) {
		return new OrderProbeResult(OrderProbeStatus.UNKNOWN, null, null, null, rawMessage);
	}
}
