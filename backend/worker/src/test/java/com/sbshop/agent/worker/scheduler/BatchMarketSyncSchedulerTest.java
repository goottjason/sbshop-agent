package com.sbshop.agent.worker.scheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sbshop.agent.core.application.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class BatchMarketSyncSchedulerTest {
	@Test
	void slowMarketDoesNotBlockOtherMarketsOrSourceScheduler() throws Exception {
		var prices = mock(MarketPriceSyncService.class);
		var stocks = mock(MarketStockSyncService.class);
		var scheduler = new BatchMarketSyncScheduler(prices, stocks);
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var other = new CountDownLatch(1);
		doAnswer(call -> {
			started.countDown();
			release.await(5, TimeUnit.SECONDS);
			return null;
		})
			.when(stocks).processOne(MarketType.COUPANG);
		doAnswer(call -> {
			other.countDown();
			return null;
		}).when(stocks).processOne(MarketType.ELEVEN_STREET);
		try (var context = new AnnotationConfigApplicationContext(MarketInspectionSchedulingConfig.class)) {
			var pool = context.getBean("batchMarketTaskScheduler", ThreadPoolTaskScheduler.class);
			try {
				pool.execute(scheduler::coupang);
				assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
				pool.execute(scheduler::elevenst);
				assertThat(other.await(2, TimeUnit.SECONDS)).isTrue();
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void priceAndStockAlternateAndAnExceptionDoesNotSkipTheOtherField() {
		var prices = mock(MarketPriceSyncService.class);
		var stocks = mock(MarketStockSyncService.class);
		var scheduler = new BatchMarketSyncScheduler(prices, stocks);
		doThrow(new IllegalStateException("temporary")).when(stocks).processOne(MarketType.COUPANG);
		scheduler.coupang();
		scheduler.coupang();
		var ordered = inOrder(prices, stocks);
		ordered.verify(stocks).processOne(MarketType.COUPANG);
		ordered.verify(prices, times(2)).processOne(MarketType.COUPANG);
		ordered.verify(stocks).processOne(MarketType.COUPANG);
	}
}
