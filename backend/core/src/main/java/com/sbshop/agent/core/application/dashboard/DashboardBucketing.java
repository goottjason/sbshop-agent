package com.sbshop.agent.core.application.dashboard;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** 대시보드 캘린더 버킷팅(KST, 월요일 주, 달력 월). 순수함수. */
public final class DashboardBucketing {
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private DashboardBucketing() {}

	/** zone 없는 UTC 벽시계값을 KST 날짜로 본 뒤, unit 버킷 시작일로 내린다. */
	public static LocalDate bucketKey(LocalDateTime naiveUtc, Unit unit) {
		LocalDate kst = naiveUtc.atZone(ZoneId.of("UTC")).withZoneSameInstant(KST).toLocalDate();
		return floor(kst, unit);
	}

	private static LocalDate floor(LocalDate d, Unit unit) {
		return switch (unit) {
			case DAY -> d;
			case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			case MONTH -> d.withDayOfMonth(1);
		};
	}

	private static LocalDate next(LocalDate bucket, Unit unit) {
		return switch (unit) {
			case DAY -> bucket.plusDays(1);
			case WEEK -> bucket.plusWeeks(1);
			case MONTH -> bucket.plusMonths(1);
		};
	}

	/** [start,end] 구간의 모든 버킷 시작일을 빈 구간 포함 오름차순으로.
	 *  구간 경계(start/end)는 naive 날짜 부분으로 버킷을 결정한다(대시보드 쿼리 파라미터 용도). */
	public static List<LocalDate> bucketRange(LocalDateTime start, LocalDateTime end, Unit unit) {
		LocalDate first = floor(start.toLocalDate(), unit);
		LocalDate last = floor(end.toLocalDate(), unit);
		List<LocalDate> out = new ArrayList<>();
		for (LocalDate b = first; !b.isAfter(last); b = next(b, unit)) {
			out.add(b);
		}
		return out;
	}
}
