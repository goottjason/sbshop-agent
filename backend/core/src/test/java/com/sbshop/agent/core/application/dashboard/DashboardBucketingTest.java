package com.sbshop.agent.core.application.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DashboardBucketingTest {

	// orderDate는 zone 없는 UTC 벽시계값. KST=UTC+9. 2026-07-01T20:00Z → KST 07-02 05:00 → 일버킷 07-02.
	@Test
	@DisplayName("DAY 버킷은 KST 날짜로 매핑(UTC+9 경계)")
	void dayBucketUsesKst() {
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-01T20:00:00"), Unit.DAY))
			.isEqualTo(LocalDate.parse("2026-07-02"));
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-01T10:00:00"), Unit.DAY))
			.isEqualTo(LocalDate.parse("2026-07-01"));
	}

	@Test
	@DisplayName("WEEK 버킷은 월요일 시작(ISO)")
	void weekBucketStartsMonday() {
		// 2026-07-25(토, KST) → 그 주 월요일 2026-07-20
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-25T03:00:00"), Unit.WEEK))
			.isEqualTo(LocalDate.parse("2026-07-20"));
	}

	@Test
	@DisplayName("MONTH 버킷은 달력 1일")
	void monthBucketIsFirstOfMonth() {
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-25T03:00:00"), Unit.MONTH))
			.isEqualTo(LocalDate.parse("2026-07-01"));
	}

	@Test
	@DisplayName("bucketRange는 빈 구간 포함 모든 버킷을 오름차순으로 채운다(DAY)")
	void dayRangeFillsEmpty() {
		List<LocalDate> r = DashboardBucketing.bucketRange(
			LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-03T23:59:59"), Unit.DAY);
		assertThat(r).containsExactly(
			LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-03"));
	}

	@Test
	@DisplayName("bucketRange WEEK는 월요일 시작 버킷을 채운다")
	void weekRangeMondays() {
		List<LocalDate> r = DashboardBucketing.bucketRange(
			LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-20T00:00:00"), Unit.WEEK);
		// 07-01(수) 속한 주 월요일=06-29, 이후 07-06, 07-13, 07-20
		assertThat(r).containsExactly(
			LocalDate.parse("2026-06-29"), LocalDate.parse("2026-07-06"),
			LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-20"));
	}
}
