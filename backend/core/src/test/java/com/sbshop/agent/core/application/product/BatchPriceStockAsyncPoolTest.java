package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.config.AsyncConfig;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * D-011 재현/회귀 테스트.
 *
 * <p>{@link BatchPriceStockService}의 배치 메서드는 {@code @Async("productBatchExecutor")}로 전용 풀에
 * 라우팅되어야 한다. 두 가지 회귀를 함께 방어한다:
 * <ol>
 *   <li>한정자를 제거하면(bare {@code @Async}) executor 빈이 복수(core의 {@code syncTaskExecutor} +
 *       {@code productBatchExecutor})일 때 전용 풀을 특정 못 해 {@code SimpleAsyncTaskExecutor}로 폴백 →
 *       {@code product-batch-} 스레드 미사용.</li>
 *   <li>{@code productBatchExecutor} 빈이 core에 없으면(api 모듈로만 정의하면) worker처럼 api 설정을 갖지
 *       않는 컨텍스트에서 한정자가 해소되지 않아 폴백.</li>
 * </ol>
 *
 * <p>이 테스트는 <b>실제 core {@link AsyncConfig}만</b> 로드한다(api 설정 미포함). 이는 worker 실행
 * 컨텍스트({@code WorkerApplication}은 core+infrastructure만 스캔, api 미의존)와 동일한 조건이므로,
 * 빈이 core에 승격된 뒤 worker의 일일 05시 cron({@code BatchScheduler})에서도 한정자가 해소되어
 * {@code product-batch-} 전용 풀로 라우팅됨을 보장한다.
 */
@SpringJUnitConfig({AsyncConfig.class, BatchPriceStockAsyncPoolTest.TestBeans.class})
class BatchPriceStockAsyncPoolTest {

	@Autowired
	private BatchPriceStockService service;

	@Autowired
	private TestBeans testBeans;

	@Test
	void crawlBatchRunsOnDedicatedProductBatchPoolInCoreOnlyContext() throws InterruptedException {
		service.crawlAndUpdatePriceStock("batch-1", List.of(), BigDecimal.ONE, BigDecimal.ZERO,
			BigDecimal.ZERO,
			com.sbshop.agent.core.domain.actionlog.ActionLogConstants.BATCH_CRAWL_UPDATE);

		assertThat(testBeans.completed.await(5, TimeUnit.SECONDS))
			.as("배치가 비동기로 실행되어 완료 이벤트를 발행해야 함").isTrue();
		assertThat(testBeans.executionThread.get())
			.as("worker 유형(core 설정만) 컨텍스트에서도 productBatchExecutor 전용 풀(product-batch-)로 라우팅되어야 함")
			.startsWith("product-batch-");
	}

	@Test
	void allBatchMethodsDeclareProductBatchExecutorQualifier() {
		for (String methodName : List.of("crawlAndUpdatePriceStock", "manualUpdatePriceStock",
			"manualUpdateAllFields")) {
			Method method = findMethod(methodName);
			Async async = method.getAnnotation(Async.class);
			assertThat(async).as("%s 는 @Async 여야 함", methodName).isNotNull();
			assertThat(async.value())
				.as("%s 의 @Async 는 productBatchExecutor 한정자를 지정해야 함", methodName)
				.isEqualTo("productBatchExecutor");
		}
	}

	private static Method findMethod(String name) {
		for (Method m : BatchPriceStockService.class.getDeclaredMethods()) {
			if (m.getName().equals(name)) {
				return m;
			}
		}
		throw new IllegalStateException("메서드 없음: " + name);
	}

	@Configuration
	static class TestBeans {

		final CountDownLatch completed = new CountDownLatch(1);
		final AtomicReference<String> executionThread = new AtomicReference<>();

		@Bean
		BatchPriceStockService batchPriceStockService() {
			ApplicationEventPublisher recordingPublisher = event -> {
				executionThread.set(Thread.currentThread().getName());
				completed.countDown();
			};
			return new BatchPriceStockService(mock(ProductReader.class), mock(ProductWriter.class),
				mock(ProductRepository.class), mock(ProductStockCrawlerPort.class),
				mock(ProcessStatusService.class), mock(MarginCalculator.class), recordingPublisher,
				mock(ProductMarketSyncService.class));
		}
	}
}
