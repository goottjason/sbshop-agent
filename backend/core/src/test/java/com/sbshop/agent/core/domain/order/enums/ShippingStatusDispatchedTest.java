package com.sbshop.agent.core.domain.order.enums;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ShippingStatusDispatchedTest {

	@Test
	void dispatched_존재하고_order_2이며_label은_배송지시() {
		assertThat(ShippingStatus.DISPATCHED).isNotNull();
		assertThat(ShippingStatus.DISPATCHED.getOrder()).isEqualTo(2);
		assertThat(ShippingStatus.DISPATCHED.getLabel()).isEqualTo("배송지시");
	}

	@Test
	void dispatched_순서는_PREPARING_보다_크고_SHIPPED_보다_작다() {
		assertThat(ShippingStatus.DISPATCHED.getOrder())
			.isGreaterThan(ShippingStatus.PREPARING.getOrder())
			.isLessThan(ShippingStatus.SHIPPED.getOrder());
	}
}
