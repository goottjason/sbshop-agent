package com.sbshop.agent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.GenericApplicationContext;

class AsyncConfigBeanNameConflictTest {

	@Test
	void coreAndApiAsyncConfigShouldRegisterWithoutBeanNameConflict() {
		try (GenericApplicationContext ctx = new GenericApplicationContext()) {
			ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(ctx);
			scanner.scan(
				"com.sbshop.agent.core.config",
				"com.sbshop.agent.api.config");

			assertThat(ctx.containsBeanDefinition("asyncConfig")).isTrue();
			assertThat(ctx.containsBeanDefinition("apiAsyncConfig")).isTrue();
		}
	}
}
