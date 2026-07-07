package com.sbshop.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * D-001 회귀 테스트 (D-010 리네임 반영).
 * <p>
 * elevenst 패키지에는 용도가 다른 두 개의 REST 클라이언트가 존재한다
 * ({@code ElevenstOrderRestClient}: 주문 API용 GET 전용, {@code ElevenstMarketRestClient}: 상품 API용
 * GET/POST/PUT). 과거 두 클래스는 단순명 {@code ElevenstRestClient}로 동일해 기본 빈 이름
 * {@code elevenstRestClient} 충돌({@code ConflictingBeanDefinitionException})로 API 컨텍스트가 시작하지
 * 못했다(D-001). D-010에서 역할 드러나는 이름으로 리네임해 근본 원인을 제거했다. 이 테스트는 컴포넌트
 * 스캔이 충돌 없이 완료되고 두 클라이언트가 각각 별개의 빈으로 등록됨을 보장한다.
 */
class ElevenstRestClientBeanConflictTest {

	@Test
	void elevenstRestClientClassesShouldRegisterWithoutBeanNameConflict() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.scan("com.sbshop.agent.infrastructure.client.elevenst");
			ctx.refresh();

			assertThat(ctx.getBean(
				com.sbshop.agent.infrastructure.client.elevenst.ElevenstOrderRestClient.class)).isNotNull();
			assertThat(ctx.getBean(
				com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient.class)).isNotNull();
		}
	}
}
