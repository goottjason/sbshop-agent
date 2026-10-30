package com.sbshop.agent.core.application.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DashboardBucketingTest {

	@Test
	@DisplayName("DAY 버킷은 주문일(KST 벽시계)의 날짜 그대로 — 시간대 환산 없음")
	void dayBucketUsesKstWallClock() {
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-01T20:00:00"), Unit.DAY))
			.isEqualTo(LocalDate.parse("2026-07-01"));
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-01T23:59:59"), Unit.DAY))
			.isEqualTo(LocalDate.parse("2026-07-01"));
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-01T00:00:00"), Unit.DAY))
			.isEqualTo(LocalDate.parse("2026-07-01"));
	}

	@Test
	@DisplayName("일요일 밤 주문은 그 주, 말일 밤 주문은 그 달 버킷")
	void lateNightStaysInSameWeekAndMonth() {
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-10-25T23:00:00"), Unit.WEEK))
			.isEqualTo(LocalDate.parse("2026-10-19"));
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-10-31T23:00:00"), Unit.MONTH))
			.isEqualTo(LocalDate.parse("2026-10-01"));
	}

	@Test
	@DisplayName("WEEK 버킷은 월요일 시작(ISO)")
	void weekBucketStartsMonday() {
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
		assertThat(r).containsExactly(
			LocalDate.parse("2026-06-29"), LocalDate.parse("2026-07-06"),
			LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-20"));
	}
}
