package com.sbshop.agent.infrastructure.lock;

import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PostgresAdvisoryTokenRefreshLock implements TokenRefreshLock {

	private static final long LOCK_TX_TIMEOUT_MS = 15000L;

	private final JdbcTemplate jdbcTemplate;

	public PostgresAdvisoryTokenRefreshLock(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional
	public <T> T runExclusively(long key, Supplier<T> action) {
		jdbcTemplate.execute("SET LOCAL statement_timeout = '" + LOCK_TX_TIMEOUT_MS + "'");
		jdbcTemplate.execute("SET LOCAL idle_in_transaction_session_timeout = '" + LOCK_TX_TIMEOUT_MS + "'");
		jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, key);
		return action.get();
	}
}
