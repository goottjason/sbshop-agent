package com.sbshop.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * D-001 회귀 테스트.
 * <p>
 * elevenst 패키지에는 서로 다른 두 개의 {@code ElevenstRestClient} 클래스가 존재한다
 * (구버전: 주문 API용 GET 전용, 신버전: 상품 API용 GET/POST/PUT). 두 클래스가 모두
 * 기본 빈 이름 {@code elevenstRestClient}로 스캔되면 {@code ConflictingBeanDefinitionException}이
 * 발생해 API 컨텍스트 자체가 시작하지 못한다. 이 테스트는 컴포넌트 스캔이 충돌 없이 완료되고
 * 두 클라이언트가 각각 별개의 빈으로 등록됨을 보장한다.
 */
class ElevenstRestClientBeanConflictTest {

	@Test
	void elevenstRestClientClassesShouldRegisterWithoutBeanNameConflict() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.scan("com.sbshop.agent.infrastructure.client.elevenst");
			ctx.refresh();

			assertThat(ctx.getBean(
				com.sbshop.agent.infrastructure.client.elevenst.ElevenstRestClient.class)).isNotNull();
			assertThat(ctx.getBean(
				com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstRestClient.class)).isNotNull();
		}
	}
}
