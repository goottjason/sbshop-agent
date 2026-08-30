package com.sbshop.agent.infrastructure.client.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FxRateClientTest {

	private static class Stub extends FxRateClient {
		final AtomicInteger calls = new AtomicInteger();
		BigDecimal next = new BigDecimal("1868.34");

		Stub() {
			super(new ObjectMapper());
		}

		@Override
		protected BigDecimal fetch(String base) {
			calls.incrementAndGet();
			return next;
		}
	}

	@Test
	@DisplayName("KRW 는 조회하지 않고 1.0 이다 — 원화 표기 소싱처가 환율 장애에 끌려가면 안 된다")
	void krwNeedsNoLookup() {
		Stub fx = new Stub();
		assertThat(fx.toKrw("KRW")).isEqualByComparingTo("1");
		assertThat(fx.calls.get()).isZero();
	}

	@Test
	@DisplayName("같은 통화를 반복 조회해도 한 번만 가져온다 — 배치가 수백 건을 돌린다")
	void cachesPerCurrency() {
		Stub fx = new Stub();
		fx.toKrw("GBP");
		fx.toKrw("GBP");
		fx.toKrw("gbp");
		assertThat(fx.calls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("통화마다 따로 조회한다 — 파운드 환율을 달러에 쓰면 안 된다")
	void separateCachePerCurrency() {
		Stub fx = new Stub();
		fx.toKrw("GBP");
		fx.toKrw("USD");
		assertThat(fx.calls.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("환율이 없거나 0 이면 던진다 — 0 으로 계산하면 원가가 0 이 된다")
	void refusesUnusableRate() {
		Stub zero = new Stub();
		zero.next = BigDecimal.ZERO;
		assertThatThrownBy(() -> zero.toKrw("GBP")).isInstanceOf(IllegalStateException.class);

		Stub none = new Stub();
		none.next = null;
		assertThatThrownBy(() -> none.toKrw("GBP")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("통화가 비어 있으면 던진다 — 임의 통화로 추정하지 않는다")
	void refusesBlankCurrency() {
		Stub fx = new Stub();
		assertThatThrownBy(() -> fx.toKrw(null)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> fx.toKrw("  ")).isInstanceOf(IllegalStateException.class);
	}
}
