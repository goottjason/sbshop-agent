package com.sbshop.agent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.GenericApplicationContext;

/**
 * D-009 회귀 테스트.
 * <p>
 * {@code core.config.AsyncConfig}와 {@code api.config.AsyncConfig}는 둘 다 {@code @Configuration}이고
 * 클래스 단순명이 같아 기본 빈 이름 {@code asyncConfig}로 충돌한다
 * ({@code ConflictingBeanDefinitionException}) → API 컨텍스트 시작 불가.
 * <p>
 * 두 config 패키지를 함께 스캔했을 때 충돌 없이 각각 별개의 빈 정의로 등록됨을 보장한다.
 * 빈 이름 충돌은 스캔 시점에 발생하므로 refresh 없이 빈 정의만 확인한다
 * (core.config의 {@code @EnableJpaAuditing} 등 인프라 초기화를 유발하지 않기 위함).
 */
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
