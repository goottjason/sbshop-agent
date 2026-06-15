package com.sbshop.agent.core.domain.order.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashSet;
import java.util.Set;

/**
 * 한국 공휴일을 반영한 영업일 계산 유틸리티
 */
public class BusinessDayCalculator {

	private static final Set<LocalDate> FIXED_HOLIDAYS = new HashSet<>();

	static {
		// 매년 고정 공휴일
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.JANUARY, 1)); // 신정
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.MARCH, 1)); // 삼일절
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.MAY, 1)); // 근로자의 날
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.MAY, 5)); // 어린이날
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.JUNE, 6)); // 현충일
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.AUGUST, 15)); // 광복절
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.OCTOBER, 3)); // 개천절
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.OCTOBER, 9)); // 한글날
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.DECEMBER, 25)); // 크리스마스

		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.JANUARY, 1)); // 신정
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.MARCH, 1)); // 삼일절
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.MAY, 1)); // 근로자의 날
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.MAY, 5)); // 어린이날
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.JUNE, 6)); // 현충일
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.AUGUST, 15)); // 광복절
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.OCTOBER, 3)); // 개천절
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.OCTOBER, 9)); // 한글날
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.DECEMBER, 25)); // 크리스마스

		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.JANUARY, 1)); // 신정
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.MARCH, 1)); // 삼일절
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.MAY, 1)); // 근로자의 날
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.MAY, 5)); // 어린이날
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.JUNE, 6)); // 현충일
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.AUGUST, 15)); // 광복절
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.OCTOBER, 3)); // 개천절
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.OCTOBER, 9)); // 한글날
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.DECEMBER, 25)); // 크리스마스

		// 2024 음력 공휴일 (설날, 추석 등 - 대략적 계산)
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.FEBRUARY, 9)); // 설날 전날
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.FEBRUARY, 10)); // 설날
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.FEBRUARY, 11)); // 설날 다음날
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.FEBRUARY, 12)); // 대체공휴일
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.SEPTEMBER, 16)); // 추석
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.SEPTEMBER, 17)); // 추석
		FIXED_HOLIDAYS.add(LocalDate.of(2024, Month.SEPTEMBER, 18)); // 추석 대체

		// 2025 음력 공휴일
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.JANUARY, 28)); // 설날
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.JANUARY, 29)); // 설날
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.JANUARY, 30)); // 설날 대체
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.OCTOBER, 5)); // 추석
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.OCTOBER, 6)); // 추석
		FIXED_HOLIDAYS.add(LocalDate.of(2025, Month.OCTOBER, 7)); // 추석 대체

		// 2026 음력 공휴일
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.FEBRUARY, 16)); // 설날
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.FEBRUARY, 17)); // 설날
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.FEBRUARY, 18)); // 설날 대체
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.SEPTEMBER, 24)); // 추석
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.SEPTEMBER, 25)); // 추석
		FIXED_HOLIDAYS.add(LocalDate.of(2026, Month.SEPTEMBER, 26)); // 추석 대체
	}

	/**
	 * 주말이 아닌지 확인
	 */
	private static boolean isWeekend(LocalDate date) {
		DayOfWeek dow = date.getDayOfWeek();
		return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
	}

	/**
	 * 공휴일인지 확인 (고정 공휴일 + 주말)
	 */
	private static boolean isHoliday(LocalDate date) {
		return isWeekend(date) || FIXED_HOLIDAYS.contains(date);
	}

	/**
	 * 특정 날짜로부터 영업일 기준으로 N일 후 날짜 계산
	 * 영업일 = 평일 + 공휴일 제외
	 * @param startDate 시작일 (포함)
	 * @param businessDaysToAdd 더할 영업일 수
	 * @return 영업일 기준 N일 후 날짜
	 */
	public static LocalDate addBusinessDays(LocalDate startDate, int businessDaysToAdd) {
		LocalDate current = startDate;
		int added = 0;
		while (added < businessDaysToAdd) {
			current = current.plusDays(1);
			if (!isHoliday(current)) {
				added++;
			}
		}
		return current;
	}

	/**
	 * 두 날짜 사이의 영업일 수 계산 (시작일 포함, 종료일 불포함)
	 */
	public static long countBusinessDays(LocalDate from, LocalDate to) {
		long count = 0;
		LocalDate current = from;
		while (current.isBefore(to)) {
			current = current.plusDays(1);
			if (!isHoliday(current)) {
				count++;
			}
		}
		return count;
	}
}
