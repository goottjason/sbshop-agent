package com.sbshop.agent.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = EntityMappingTest.TestApp.class)
class EntityMappingTest {

	@SpringBootApplication
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.dummy")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Test
	void testOrderEntityMapping() {
		Order order = Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("C12345")
			.orderDate(LocalDateTime.now())
			.recipientName("홍길동")
			.recipientPhone("010-1234-5678")
			.build();

		em.persist(order);
		em.flush();
		em.clear();

		Order found = em.find(Order.class, order.getId());
		assertThat(found).isNotNull();
		assertThat(found.getMarketOrderNo()).isEqualTo("C12345");
		assertThat(found.getMarketType()).isEqualTo(MarketType.COUPANG);
		assertThat(found.getStatus().name()).isEqualTo("ACTIVE");
	}
}
