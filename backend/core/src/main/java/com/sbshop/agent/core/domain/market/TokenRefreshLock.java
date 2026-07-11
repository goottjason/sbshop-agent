package com.sbshop.agent.core.domain.market;

import java.util.function.Supplier;

/**
 * 프로세스 간 상호배제로 임계 구역(토큰 갱신 등)을 직렬화하는 포트.
 * 동일 key에 대해 한 번에 하나의 호출만 action을 실행하도록 보장한다(다중 JVM 포함).
 */
public interface TokenRefreshLock {
	<T> T runExclusively(long key, Supplier<T> action);
}
