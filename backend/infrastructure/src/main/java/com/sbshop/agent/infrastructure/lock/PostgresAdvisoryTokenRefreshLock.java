package com.sbshop.agent.infrastructure.lock;

import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres 트랜잭션 범위 advisory lock으로 임계 구역을 프로세스 간 직렬화한다.
 * 락은 트랜잭션 커밋/롤백 시 자동 해제되며, 다른 프로세스는 같은 key에서 블록된다.
 * action 내부의 JPA 저장은 이 트랜잭션에 참여하므로 refresh + 영속화가 원자적이다.
 */
@Component
public class PostgresAdvisoryTokenRefreshLock implements TokenRefreshLock {

	private final JdbcTemplate jdbcTemplate;

	public PostgresAdvisoryTokenRefreshLock(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional
	public <T> T runExclusively(long key, Supplier<T> action) {
		jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, key);
		return action.get();
	}
}
