package com.sbshop.agent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.config.AsyncConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

class AsyncConfigStructureTest {

	@Test
	@DisplayName("api.config 에는 asyncConfig 껍데기가 없고 core.config 판만 남는다")
	void onlyCoreAsyncConfigIsRegistered() {
		try (GenericApplicationContext ctx = new GenericApplicationContext()) {
			ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(ctx);
			scanner.scan(
				"com.sbshop.agent.core.config",
				"com.sbshop.agent.api.config");

			assertThat(ctx.containsBeanDefinition("asyncConfig")).isTrue();
			assertThat(ctx.containsBeanDefinition("apiAsyncConfig")).isFalse();
		}
	}

	@Test
	@DisplayName("core.config.AsyncConfig 단독으로 @EnableAsync 후처리기가 등록된다")
	void coreAsyncConfigAloneEnablesAsyncProcessing() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
			assertThat(ctx.containsBean(TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue();
		}
	}
}
