package com.sbshop.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * D-003 회귀 테스트 (D-011로 core 이전).
 *
 * <p>원래 api {@code AsyncConfig}에 있던 {@code productBatchExecutor} 빈은 D-011에서 core
 * {@code AsyncConfig}로 승격됐다(호출자가 api·worker 양쪽이라 core에 있어야 두 컨텍스트 모두 한정자 해소).
 * 이 테스트는 검증 의도(빈 등록 + 이름 해소)를 유지한 채 검증 위치만 core로 옮긴 것이다. core
 * {@code AsyncConfig}를 격리 컨텍스트로 올려 {@code productBatchExecutor} 빈이 {@code product-batch-}
 * 전용 풀로 등록됨을 보장한다.
 */
class ProductBatchExecutorBeanTest {

	@Test
	void productBatchExecutorShouldBeRegisteredInCoreAsyncConfig() {
		try (AnnotationConfigApplicationContext ctx =
				new AnnotationConfigApplicationContext(AsyncConfig.class)) {

			Executor executor = ctx.getBean("productBatchExecutor", Executor.class);
			assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
			assertThat(((ThreadPoolTaskExecutor) executor).getThreadNamePrefix())
					.isEqualTo("product-batch-");
		}
	}
}
