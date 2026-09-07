package com.sbshop.agent.core.domain.market.client;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/** Same-thread request fence for reviewed preparation. Existing calls outside a scope remain unchanged. */
public final class MarketPreparationRequestScope implements AutoCloseable {
	private static final ThreadLocal<MarketPreparationRequestScope> CURRENT = new ThreadLocal<>();
	private final MarketType market;
	private final Runnable guard;
	private final Consumer<Instant> rateLimited;

	private MarketPreparationRequestScope(MarketType market, Runnable guard, Consumer<Instant> rateLimited) {
		this.market = Objects.requireNonNull(market);
		this.guard = Objects.requireNonNull(guard);
		this.rateLimited = Objects.requireNonNull(rateLimited);
	}

	public static MarketPreparationRequestScope open(MarketType market, Runnable guard, Consumer<Instant> rateLimited) {
		if (CURRENT.get() != null)
			throw new Blocked("등록 준비 요청 범위를 중첩할 수 없습니다.");
		var scope = new MarketPreparationRequestScope(market, guard, rateLimited);
		CURRENT.set(scope);
		return scope;
	}

	public static boolean active() {
		return CURRENT.get() != null;
	}

	public static void beforeRequest(MarketType market) {
		var scope = CURRENT.get();
		if (scope != null) {
			if (scope.market != market)
				throw new Blocked("검토한 등록 준비 마켓과 요청 마켓이 다릅니다.");
			scope.guard.run();
		}
	}

	public static void observedRateLimit(MarketType market, Instant retryAfter) {
		var scope = CURRENT.get();
		if (scope != null) {
			if (scope.market != market)
				throw new Blocked("등록 준비 마켓 범위가 다릅니다.");
			scope.rateLimited.accept(retryAfter);
		}
	}

	@Override
	public void close() {
		if (CURRENT.get() != this)
			throw new Blocked("등록 준비 요청 범위 소유권이 변경되었습니다.");
		CURRENT.remove();
	}

	public static final class Blocked extends IllegalStateException {
		public Blocked(String detail) {
			super(detail);
		}
	}
}
