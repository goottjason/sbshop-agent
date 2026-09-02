package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

class LineItemClaimTest {

	private OrderLineItem item(ShippingStatus status) {
		return OrderLineItem.builder().orderId(1L).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	@Test
	@DisplayName("클레임을 붙여도 배송 단계는 그대로다 — 두 축은 서로 덮지 않는다")
	void claimDoesNotOverwriteDeliveryStage() {
		OrderLineItem li = item(ShippingStatus.DELIVERED);

		li.applyClaim(ClaimData.builder()
			.claimType(ClaimType.EXCHANGE).claimStage(ClaimStage.REQUESTED).claimRawCode("E00").build());

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(li.getClaimData().getLabel()).isEqualTo("교환 요청");
	}

	@Test
	@DisplayName("교환이 끝나고 재발송되면 배송중으로 돌아온다 — 교환은 경유지다")
	void exchangeDoneKeepsShippingStage() {
		OrderLineItem li = item(ShippingStatus.DELIVERED);
		li.applyClaim(ClaimData.builder()
			.claimType(ClaimType.EXCHANGE).claimStage(ClaimStage.DONE).build());

		li.applyShippingData(li.getShippingData().toBuilder()
			.shippingStatus(ShippingStatus.SHIPPED).build());

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(li.isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("반품 완료면 환불 대상이다 — 정산액을 0 으로 만들 근거가 된다")
	void returnDoneIsRefundTerminal() {
		OrderLineItem li = item(ShippingStatus.DELIVERED);

		li.applyClaim(ClaimData.builder()
			.claimType(ClaimType.RETURN).claimStage(ClaimStage.DONE).build());

		assertThat(li.isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("클레임이 없으면 환불 대상이 아니다")
	void noClaimIsNotRefundTerminal() {
		assertThat(item(ShippingStatus.DELIVERED).isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("클레임 기본값은 없음이다 — 기존 라인아이템도 안전하게 읽힌다")
	void defaultsToNoClaim() {
		assertThat(item(ShippingStatus.NEW).getClaimData().getClaimType()).isEqualTo(ClaimType.NONE);
	}
}
