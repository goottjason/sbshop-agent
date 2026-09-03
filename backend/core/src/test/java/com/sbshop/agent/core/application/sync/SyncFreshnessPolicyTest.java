package com.sbshop.agent.core.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyncFreshnessPolicyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 28, 10, 0);
	private static final int WINDOW_DAYS = 90;

	@Test
	@DisplayName("D-282: 90일에 24건인 마켓은 임계가 하드코딩 2일이 아니라 평균간격 기반 11일이다")
	void lowVolumeMarketGetsThresholdFromAverageInterval() {
		Duration threshold = SyncFreshnessPolicy.threshold(24, WINDOW_DAYS);

		assertThat(threshold.toDays()).isEqualTo(11);
	}

	@Test
	@DisplayName("D-282: 90일에 75건인 고빈도 마켓은 임계가 짧은 3일대다")
	void highVolumeMarketGetsShortThreshold() {
		Duration threshold = SyncFreshnessPolicy.threshold(75, WINDOW_DAYS);

		assertThat(threshold.toDays()).isEqualTo(3);
	}

	@Test
	@DisplayName("D-282: 초저빈도 마켓은 임계가 상한 30일에서 멈춘다")
	void veryLowVolumeMarketClampsToMax() {
		Duration threshold = SyncFreshnessPolicy.threshold(8, WINDOW_DAYS);

		assertThat(threshold.toDays()).isEqualTo(30);
	}

	@Test
	@DisplayName("D-282: 초고빈도 마켓도 임계가 하한 2일 밑으로 내려가지 않는다")
	void veryHighVolumeMarketClampsToMin() {
		Duration threshold = SyncFreshnessPolicy.threshold(1000, WINDOW_DAYS);

		assertThat(threshold.toDays()).isEqualTo(2);
	}

	@Test
	@DisplayName("D-282: 관측창 내 주문이 0건이면 0으로 나누지 않고 상한 30일을 쓴다")
	void zeroOrdersInWindowUsesMax() {
		Duration threshold = SyncFreshnessPolicy.threshold(0, WINDOW_DAYS);

		assertThat(threshold).isEqualTo(Duration.ofDays(30));
	}

	@Test
	@DisplayName("D-282: 저빈도 마켓(90일 24건)은 임계 이내 공백에는 경보하지 않는다")
	void lowVolumeMarketDoesNotWarnWithinThreshold() {
		Optional<Duration> stale = SyncFreshnessPolicy.staleness(
			24, WINDOW_DAYS, NOW.minusDays(10), NOW);

		assertThat(stale).isEmpty();
	}

	@Test
	@DisplayName("D-282: 저빈도 마켓(90일 24건)은 임계를 넘긴 공백에 경보한다")
	void lowVolumeMarketWarnsBeyondThreshold() {
		Optional<Duration> stale = SyncFreshnessPolicy.staleness(
			24, WINDOW_DAYS, NOW.minusDays(12), NOW);

		assertThat(stale).isPresent();
		assertThat(stale.get().toDays()).isEqualTo(12);
	}

	@Test
	@DisplayName("D-282: 스토어의 실제 주문 밀도(90일 24건 수준)에서 2.6일 공백은 경보가 아니다")
	void storeActualDensityDoesNotWarnOnShortGap() {
		LocalDateTime lastNewAt = NOW.minus(Duration.ofHours(62));

		Optional<Duration> stale = SyncFreshnessPolicy.staleness(24, WINDOW_DAYS, lastNewAt, NOW);

		assertThat(stale).isEmpty();
	}

	@Test
	@DisplayName("D-282: 신규 유입 이력이 아예 없으면(null) 경고하지 않는다 — 측정 근거가 없다")
	void noLastNewAtDoesNotWarn() {
		assertThat(SyncFreshnessPolicy.staleness(24, WINDOW_DAYS, null, NOW)).isEmpty();
	}
}
