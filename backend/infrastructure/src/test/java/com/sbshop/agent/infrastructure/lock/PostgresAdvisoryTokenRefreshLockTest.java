package com.sbshop.agent.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class PostgresAdvisoryTokenRefreshLockTest {

	@Mock private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("advisory lock을 획득하고 action 결과를 반환한다")
	void acquiresLockThenRunsAction() {
		when(jdbcTemplate.queryForObject(any(String.class), eq(Object.class), eq(42L)))
			.thenReturn(0);
		var lock = new PostgresAdvisoryTokenRefreshLock(jdbcTemplate);

		String result = lock.runExclusively(42L, () -> "DONE");

		assertThat(result).isEqualTo("DONE");
		verify(jdbcTemplate)
			.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, 42L);
	}
}
