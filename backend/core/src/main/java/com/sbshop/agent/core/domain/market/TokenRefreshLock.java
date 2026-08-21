package com.sbshop.agent.core.domain.market;

import java.util.function.Supplier;

public interface TokenRefreshLock {
	<T> T runExclusively(long key, Supplier<T> action);
}
