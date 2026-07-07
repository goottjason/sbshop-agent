package com.sbshop.agent.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

	@Bean(name = "syncTaskExecutor")
	public Executor syncTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(5);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("SyncWorker-");
		executor.initialize();
		return executor;
	}

	/**
	 * 상품 가격/재고 배치 전용 스레드풀. 소비자({@code BatchPriceStockService})가 core에 있고 호출자가
	 * api(컨트롤러)와 worker(일일 cron) 양쪽이므로, 두 실행 컨텍스트 모두에서
	 * {@code @Async("productBatchExecutor")} 한정자가 해소되도록 core에 정의한다.
	 */
	@Bean(name = "productBatchExecutor")
	public Executor productBatchExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(5);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("product-batch-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		return executor;
	}
}
