package com.sbshop.agent.core.application.dashboard;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

public final class DashboardBucketing {
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private DashboardBucketing() {}

	public static LocalDate bucketKey(LocalDateTime naiveUtc, Unit unit) {
		LocalDate kst = naiveUtc.atZone(ZoneId.of("UTC")).withZoneSameInstant(KST).toLocalDate();
		return floor(kst, unit);
	}

	public static List<LocalDate> bucketRange(LocalDateTime start, LocalDateTime end, Unit unit) {
		LocalDate first = floor(start.toLocalDate(), unit);
		LocalDate last = floor(end.toLocalDate(), unit);
		List<LocalDate> out = new ArrayList<>();
		for (LocalDate b = first; !b.isAfter(last); b = next(b, unit)) {
			out.add(b);
		}
		return out;
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
}
