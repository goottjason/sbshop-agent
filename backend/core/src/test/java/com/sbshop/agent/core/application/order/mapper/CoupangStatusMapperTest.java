package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoupangStatusMapperTest {

	private final CoupangStatusMapper mapper = new CoupangStatusMapper();

	@Test
	@DisplayName("INSTRUCT(상품준비중) → PREPARING 매핑")
	void instruct_mapsToPrep() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "INSTRUCT"));
		assertThat(result).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("DEPARTURE(송장등록·추적미시작) → DISPATCHED 매핑")
	void departure_mapsToDispatched() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "DEPARTURE"));
		assertThat(result).isEqualTo(ShippingStatus.DISPATCHED);
	}

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
