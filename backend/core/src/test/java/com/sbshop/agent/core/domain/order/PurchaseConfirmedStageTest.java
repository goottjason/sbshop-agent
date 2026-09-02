package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

class PurchaseConfirmedStageTest {

	@Test
	@DisplayName("구매확정은 배송완료 다음 단계다 — 확정되면 반품·교환 청구권이 닫힌다")
	void confirmedComesAfterDelivered() {
		assertThat(ShippingStatus.CONFIRMED.getOrder())
			.isGreaterThan(ShippingStatus.DELIVERED.getOrder());
	}

	@Test
	@DisplayName("구매확정도 배송 단계일 뿐 클레임이 아니다 — 환불 대상이 아니다")
	void confirmedIsNotRefundTerminal() {
		assertThat(ShippingStatus.CONFIRMED.isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("배송 단계는 순서를 갖는다 — 클레임 값들만 순서 밖이다")
	void deliveryStagesAreOrdered() {
		assertThat(ShippingStatus.NEW.getOrder()).isLessThan(ShippingStatus.PREPARING.getOrder());
		assertThat(ShippingStatus.PREPARING.getOrder()).isLessThan(ShippingStatus.DISPATCHED.getOrder());
		assertThat(ShippingStatus.DISPATCHED.getOrder()).isLessThan(ShippingStatus.SHIPPED.getOrder());
		assertThat(ShippingStatus.SHIPPED.getOrder()).isLessThan(ShippingStatus.DELIVERED.getOrder());
	}
}
