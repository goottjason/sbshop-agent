package com.sbshop.agent.core.application.product.source;

import java.util.function.Supplier;

/** Request-local lease checks for reviewed sourcing only; legacy order crawls are outside this scope. */
public final class ProductSourceHttpGuard {
	private static final ThreadLocal<Runnable> CURRENT = new ThreadLocal<>();

	private ProductSourceHttpGuard() {}

	public static void check() {
		Runnable check = CURRENT.get();
		if (check != null)
			check.run();
	}

	public static <T> T scoped(Runnable check, Supplier<T> fetch) {
		Runnable previous = CURRENT.get();
		CURRENT.set(check);
		try {
			check();
			return fetch.get();
		} finally {
			if (previous == null)
				CURRENT.remove();
			else
				CURRENT.set(previous);
		}
	}
}
