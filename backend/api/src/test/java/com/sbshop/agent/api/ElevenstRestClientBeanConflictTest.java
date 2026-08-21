package com.sbshop.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.infrastructure.client.elevenst.ElevenstOrderRestClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ElevenstRestClientBeanConflictTest {

	@Test
	void elevenstRestClientClassesShouldRegisterWithoutBeanNameConflict() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.registerBean(MarketCredentialRepository.class,
				() -> Mockito
					.mock(MarketCredentialRepository.class));
			ctx.scan("com.sbshop.agent.infrastructure.client.elevenst");
			ctx.refresh();

			assertThat(ctx.getBean(
				ElevenstOrderRestClient.class)).isNotNull();
			assertThat(ctx.getBean(
				ElevenstMarketRestClient.class)).isNotNull();
		}
	}
}
