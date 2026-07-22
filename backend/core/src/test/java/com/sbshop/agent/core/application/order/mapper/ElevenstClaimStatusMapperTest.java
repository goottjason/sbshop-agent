package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-099: 11번가 주문상세 ordPrdStatNm → 클레임 상태 매핑. 상태명 부분일치.
 */
class ElevenstClaimStatusMapperTest {

	private final ElevenstStatusMapper mapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("취소완료 → CANCELED")
	void cancel() {
		assertThat(mapper.mapClaimStatus("2010", "취소완료")).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("반품완료 → RETURNED")
	void ret() {
		assertThat(mapper.mapClaimStatus("3010", "반품완료")).isEqualTo(ShippingStatus.RETURNED);
	}

	@Test
	@DisplayName("교환완료 → EXCHANGED")
	void exchange() {
		assertThat(mapper.mapClaimStatus("4010", "교환완료")).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("구매확정 등 정상 상태 → null(클레임 아님)")
	void normal() {
		assertThat(mapper.mapClaimStatus("901", "구매확정")).isNull();
		assertThat(mapper.mapClaimStatus("401", "배송완료")).isNull();
	}

	@Test
	@DisplayName("빈 상태명 → null")
	void blank() {
		assertThat(mapper.mapClaimStatus("", "")).isNull();
		assertThat(mapper.mapClaimStatus(null, null)).isNull();
	}
}
