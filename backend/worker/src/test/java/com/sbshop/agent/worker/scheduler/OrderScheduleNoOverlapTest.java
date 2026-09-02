package com.sbshop.agent.worker.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class OrderScheduleNoOverlapTest {

	private static final List<String> ROUTINE_SYNCS = List.of(
		"syncOrders", "syncCoupangOrders", "syncEsmplusOrders",
		"syncSmartStoreOrders", "syncElevenstOrders");

	private Set<Integer> minutesOf(String methodName) {
		for (Method m : OrderSyncScheduler.class.getDeclaredMethods()) {
			Scheduled s = m.getAnnotation(Scheduled.class);
			if (s != null && m.getName().equals(methodName)) {
				return parseMinutes(s.cron());
			}
		}
		throw new IllegalArgumentException("@Scheduled 메서드를 찾지 못했다: " + methodName);
	}

	private Set<Integer> parseMinutes(String cron) {
		String minuteField = cron.trim().split("\\s+")[1];
		Set<Integer> minutes = new TreeSet<>();
		if (minuteField.contains("/")) {
			String[] parts = minuteField.split("/");
			int start = Integer.parseInt(parts[0]);
			int step = Integer.parseInt(parts[1]);
			for (int m = start; m < 60; m += step) {
				minutes.add(m);
			}
		} else {
			minutes.add(Integer.parseInt(minuteField));
		}
		return minutes;
	}

	@Test
	@DisplayName("확증 주기는 어떤 정기 주문 동기화와도 같은 분에 시작하지 않는다 — 같은 주문 행을 두 스레드가 쓰면 순서가 비결정이다")
	void reconcile_doesNotShareMinuteWithRoutineSync() {
		Set<Integer> reconcile = minutesOf("reconcileOrders");

		Set<Integer> collisions = new LinkedHashSet<>();
		for (String sync : ROUTINE_SYNCS) {
			for (Integer minute : minutesOf(sync)) {
				if (reconcile.contains(minute)) {
					collisions.add(minute);
				}
			}
		}

		assertThat(collisions)
			.as("확증(%s)이 정기 동기화와 겹치는 분", reconcile)
			.isEmpty();
	}
}
