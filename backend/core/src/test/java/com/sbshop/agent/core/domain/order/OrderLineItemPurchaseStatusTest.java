package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import org.junit.jupiter.api.Test;

class OrderLineItemPurchaseStatusTest {
	private OrderLineItem newItem() {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.PREPARING).build())
			.build();
	}

	@Test
	void 기본값은_NOT_PURCHASED() {
		OrderLineItem item = newItem();
		assertThat(item.getPurchaseStatus()).isEqualTo(PurchaseStatus.NOT_PURCHASED);
	}

	@Test
	void updatePurchaseStatus_로_PURCHASED로_변경() {
		OrderLineItem item = newItem();
		item.updatePurchaseStatus(PurchaseStatus.PURCHASED);
		assertThat(item.getPurchaseStatus()).isEqualTo(PurchaseStatus.PURCHASED);
		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	void markAsDispatched_shippingStatus를_DISPATCHED로_변경() {
		OrderLineItem item = newItem();
		item.markAsDispatched();
		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DISPATCHED);
		assertThat(item.getPurchaseStatus()).isEqualTo(PurchaseStatus.NOT_PURCHASED);
	}
}
