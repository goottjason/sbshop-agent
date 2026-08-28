package com.sbshop.agent.core.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyncFreshnessPolicyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 28, 10, 0);

	@Test
	@DisplayName("저volume 마켓(G마켓/옥션)은 임계가 넉넉해 8일 공백으로는 경고하지 않는다")
	void gmarketDoesNotWarnWithinThreshold() {
		Optional<Duration> stale = SyncFreshnessPolicy.staleness(
			SyncMarketKeys.GMARKET, NOW.minusDays(8), NOW);

		assertThat(stale).isEmpty();
	}

	@Test
	@DisplayName("G마켓/옥션은 신규 0건이 10일을 넘기면 경고한다")
	void gmarketWarnsBeyondThreshold() {
		Optional<Duration> stale = SyncFreshnessPolicy.staleness(
			SyncMarketKeys.GMARKET, NOW.minusDays(11), NOW);

		assertThat(stale).isPresent();
		assertThat(stale.get().toDays()).isEqualTo(11);
	}

	@Test
	@DisplayName("고volume 마켓은 임계가 짧아 3일 공백에서 이미 경고한다")
	void coupangWarnsEarlier() {
		assertThat(SyncFreshnessPolicy.staleness(SyncMarketKeys.COUPANG, NOW.minusDays(3), NOW))
			.isPresent();
		assertThat(SyncFreshnessPolicy.staleness(SyncMarketKeys.COUPANG, NOW.minusDays(1), NOW))
			.isEmpty();
	}

	@Test
	@DisplayName("신규 유입 이력이 아예 없으면(null) 경고하지 않는다 — 측정 근거가 없다")
	void noLastNewAtDoesNotWarn() {
		assertThat(SyncFreshnessPolicy.staleness(SyncMarketKeys.GMARKET, null, NOW)).isEmpty();
	}

	@Test
	@DisplayName("등록되지 않은 키는 기본 임계를 쓴다")
	void unknownKeyUsesDefaultThreshold() {
		assertThat(SyncFreshnessPolicy.threshold("UNKNOWN"))
			.isEqualTo(SyncFreshnessPolicy.defaultThreshold());
	}
}
