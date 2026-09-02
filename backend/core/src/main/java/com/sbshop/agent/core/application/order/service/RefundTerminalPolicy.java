package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * 대금이 돌아가는 건인가. 정산액 0 정규화(D-098)의 단일 판단 지점이다.
 *
 * <p>D-270 으로 클레임이 {@code ClaimData} 로 옮겨졌지만, 이전이 끝나기 전까지는
 * 옛 데이터가 {@code shipping_status} 에 {@code CANCELED}/{@code RETURNED} 를 들고 있다.
 * 둘 다 보아 과도기를 견딘다.
 */
public final class RefundTerminalPolicy {
	private RefundTerminalPolicy() {}

	public static boolean isRefundTerminal(OrderLineItem item) {
		if (item == null) {
			return false;
		}
		if (item.isRefundTerminal()) {
			return true;
		}
		ShippingData shipping = item.getShippingData();
		return shipping != null && shipping.getShippingStatus() != null
			&& shipping.getShippingStatus().isRefundTerminal();
	}
}
