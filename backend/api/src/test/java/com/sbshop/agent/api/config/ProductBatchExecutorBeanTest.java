package com.sbshop.agent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * D-003 회귀 테스트.
 * <p>
 * {@link AsyncConfig#productBatchExecutor()}에 {@code @Bean}이 누락되면 스프링 컨테이너에
 * 빈으로 등록되지 않아 {@code product-batch-} 전용 스레드풀이 존재하지 않는다. 이 테스트는
 * api {@code AsyncConfig}만 격리 컨텍스트로 올려(두 AsyncConfig 빈 이름 충돌 D-009와 무관하게)
 * {@code productBatchExecutor} 빈이 실제로 등록됨을 보장한다.
 */
class ProductBatchExecutorBeanTest {

	@Test
	void productBatchExecutorShouldBeRegisteredAsBean() {
		try (AnnotationConfigApplicationContext ctx =
			new AnnotationConfigApplicationContext(AsyncConfig.class)) {

			Executor executor = ctx.getBean("productBatchExecutor", Executor.class);
			assertThat(executor).isNotNull();
		}
	}
}
