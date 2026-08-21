package com.sbshop.agent.infrastructure.repository.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderRepositoryImplDateFilterTest {

	private final OrderRepositoryImpl repo = new OrderRepositoryImpl(null, null, null);

	@Test
	@DisplayName("start·end 둘 다 없으면 필터 없음(null)")
	void bothNull_noFilter() {
		assertThat(repo.dateBetween(null, null)).isNull();
	}

	@Test
	@DisplayName("start만 있으면 하한 경계 필터가 적용된다(null 아님)")
	void startOnly_lowerBoundApplied() {
		assertThat(repo.dateBetween(LocalDateTime.of(2026, 1, 1, 0, 0), null)).isNotNull();
	}

	@Test
	@DisplayName("end만 있으면 상한 경계 필터가 적용된다(null 아님)")
	void endOnly_upperBoundApplied() {
		assertThat(repo.dateBetween(null, LocalDateTime.of(2026, 12, 31, 23, 59))).isNotNull();
	}

	@Test
	@DisplayName("start·end 둘 다 있으면 between 필터가 적용된다(null 아님)")
	void bothPresent_betweenApplied() {
		assertThat(repo.dateBetween(
			LocalDateTime.of(2026, 1, 1, 0, 0),
			LocalDateTime.of(2026, 12, 31, 23, 59))).isNotNull();
	}
}
