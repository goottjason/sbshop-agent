package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

class ElevenstProductOrderStatusMapperTest {
	private final ElevenstStatusMapper mapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("결제완료 → NEW")
	void mapsPaymentComplete() {
		assertThat(mapper.mapProductOrderStatus("결제완료")).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("배송준비중 → PREPARING")
	void mapsPreparing() {
		assertThat(mapper.mapProductOrderStatus("배송준비중")).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("발송완료·배송중 → SHIPPED")
	void mapsShipped() {
		assertThat(mapper.mapProductOrderStatus("발송완료")).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(mapper.mapProductOrderStatus("배송중")).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("배송완료·구매확정 → DELIVERED")
	void mapsDelivered() {
		assertThat(mapper.mapProductOrderStatus("배송완료")).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(mapper.mapProductOrderStatus("구매확정")).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("클레임 상태는 종결 상태로 매핑한다")
	void mapsClaims() {
		assertThat(mapper.mapProductOrderStatus("취소완료")).isEqualTo(ShippingStatus.CANCELED);
		assertThat(mapper.mapProductOrderStatus("취소신청")).isEqualTo(ShippingStatus.CANCELED);
		assertThat(mapper.mapProductOrderStatus("반품완료")).isEqualTo(ShippingStatus.RETURNED);
		assertThat(mapper.mapProductOrderStatus("반품신청")).isEqualTo(ShippingStatus.RETURNED);
		assertThat(mapper.mapProductOrderStatus("교환완료")).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("배송완료가 배송중으로 오독되지 않는다 — 부분일치 순서 고정")
	void doesNotConfuseDeliveredWithShipping() {
		assertThat(mapper.mapProductOrderStatus("배송완료")).isNotEqualTo(ShippingStatus.SHIPPED);
		assertThat(mapper.mapProductOrderStatus("배송준비중")).isNotEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("모르는 상태명은 UNKNOWN이다 — 임의로 NEW로 되돌리지 않는다")
	void unknownStaysUnknown() {
		assertThat(mapper.mapProductOrderStatus("듣보잡상태")).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("빈 값·null은 UNKNOWN이다")
	void blankStaysUnknown() {
		assertThat(mapper.mapProductOrderStatus(null)).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapProductOrderStatus("  ")).isEqualTo(ShippingStatus.UNKNOWN);
	}
}
