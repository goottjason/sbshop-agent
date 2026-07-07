package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-030 회귀 방지: 쿠팡 NONE_TRACKING(운송장 미등록 배송중)이 UNKNOWN이 아닌 SHIPPED로 매핑돼야 한다.
 */
class CoupangStatusMapperTest {

	private final CoupangStatusMapper mapper = new CoupangStatusMapper();

	@Test
	@DisplayName("[D-030] NONE_TRACKING 상태는 SHIPPED로 매핑된다")
	void noneTracking_mapsToShipped() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "NONE_TRACKING"));
		assertThat(result).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("[D-030] 회귀 방지: 기존 매핑(ACCEPT→NEW, DELIVERING→SHIPPED, 미인식→UNKNOWN)은 유지된다")
	void existingMappings_unchanged() {
		assertThat(mapper.mapStatus(Map.of("status", "ACCEPT"))).isEqualTo(ShippingStatus.NEW);
		assertThat(mapper.mapStatus(Map.of("status", "DELIVERING"))).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(mapper.mapStatus(Map.of("status", "SOME_UNKNOWN_CODE"))).isEqualTo(ShippingStatus.UNKNOWN);
	}
}
