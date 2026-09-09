package com.sbshop.agent.worker.scheduler;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class MarketInspectionSchedulingConfigTest {
	@Test
	void slowInspectionDoesNotBlockTheDefaultScheduler() throws Exception {
		try (var context = new AnnotationConfigApplicationContext(MarketInspectionSchedulingConfig.class)) {
			var inspection = context.getBean("marketInspectionTaskScheduler", ThreadPoolTaskScheduler.class);
			var normal = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
			var started = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			var otherRan = new CountDownLatch(1);
			inspection.execute(() -> {
				started.countDown();
				try {
					release.await(3, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			try {
				assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
				normal.execute(otherRan::countDown);
				assertThat(otherRan.await(2, TimeUnit.SECONDS)).isTrue();
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void slowOrderWorkDoesNotDelayBatchStatusUpdates() throws Exception {
		String schedulerName = ProductSupplierBatchScheduler.class.getMethod("tick")
			.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).scheduler();
		try (var context = new AnnotationConfigApplicationContext(MarketInspectionSchedulingConfig.class)) {
			var normal = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
			var batch = context.getBean(schedulerName, ThreadPoolTaskScheduler.class);
			var started = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			var updated = new CountDownLatch(1);
			normal.execute(() -> {
				started.countDown();
				try {
					release.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			try {
				assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
				batch.execute(updated::countDown);
				assertThat(updated.await(2, TimeUnit.SECONDS)).isTrue();
			} finally {
				release.countDown();
			}
		}
	}

}
