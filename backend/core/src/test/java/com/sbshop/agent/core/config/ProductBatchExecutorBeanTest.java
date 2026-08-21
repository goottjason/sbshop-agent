package com.sbshop.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ProductBatchExecutorBeanTest {

	@Test
	void productBatchExecutorShouldBeRegisteredInCoreAsyncConfig() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AsyncConfig.class)) {

			Executor executor = ctx.getBean("productBatchExecutor", Executor.class);
			assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
			assertThat(((ThreadPoolTaskExecutor)executor).getThreadNamePrefix())
				.isEqualTo("product-batch-");
		}
	}
}
