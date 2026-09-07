package com.sbshop.agent.worker.scheduler;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class MarketInspectionSchedulingConfig {
	// Preserve a separate default scheduler for existing order/stock jobs.
	@Bean(name = "taskScheduler")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = "taskScheduler")
	public ThreadPoolTaskScheduler taskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("scheduled-");
		return scheduler;
	}

	@Bean
	public ThreadPoolTaskScheduler marketInspectionTaskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("market-inspection-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(150);
		return scheduler;
	}
}
