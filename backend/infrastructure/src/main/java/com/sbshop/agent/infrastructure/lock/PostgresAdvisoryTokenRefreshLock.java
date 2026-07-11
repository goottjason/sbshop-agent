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

	// 락 트랜잭션이 외부 HTTP(토큰 refresh)를 감싸므로, HTTP가 멈춰도 락·커넥션을 무한 점유하지 않도록
	// 트랜잭션을 DB 레벨에서 시간 제한한다(설계의 "무한 대기 방지" 강제).
	private static final long LOCK_TX_TIMEOUT_MS = 15000L;

	@Override
	@Transactional
	public <T> T runExclusively(long key, Supplier<T> action) {
		// statement_timeout: 락 획득 대기(및 이후 SQL)의 상한. idle_in_transaction_session_timeout:
		// HTTP refresh 중 idle 상태로 락을 무한 점유하는 것을 방지(초과 시 tx abort → 락·커넥션 해제).
		jdbcTemplate.execute("SET LOCAL statement_timeout = '" + LOCK_TX_TIMEOUT_MS + "'");
		jdbcTemplate.execute("SET LOCAL idle_in_transaction_session_timeout = '" + LOCK_TX_TIMEOUT_MS + "'");
		jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, key);
		return action.get();
	}
}
