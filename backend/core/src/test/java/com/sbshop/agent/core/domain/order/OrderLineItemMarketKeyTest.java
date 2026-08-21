package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = OrderLineItemMarketKeyTest.TestApp.class)
class OrderLineItemMarketKeyTest {
	@SpringBootApplication
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.dummy")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("마켓 상품주문번호와 배송 FK를 저장하고 다시 읽는다")
	void persistsMarketKeys() {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.marketLineItemNo("1")
			.shipmentId(7L)
			.build();

		em.persist(item);
		em.flush();
		em.clear();

		OrderLineItem found = em.find(OrderLineItem.class, item.getId());
		assertThat(found.getMarketLineItemNo()).isEqualTo("1");
		assertThat(found.getShipmentId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("두 키를 생략해도 기존처럼 저장된다 (레거시 행 호환)")
	void allowsNullMarketKeys() {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.build();

		em.persist(item);
		em.flush();
		em.clear();

		OrderLineItem found = em.find(OrderLineItem.class, item.getId());
		assertThat(found.getMarketLineItemNo()).isNull();
		assertThat(found.getShipmentId()).isNull();
	}

	@Test
	@DisplayName("assign 메서드로 나중에 채울 수 있다")
	void assignsAfterCreation() {
		OrderLineItem item = OrderLineItem.builder().orderId(100L).quantity(1).build();

		item.assignMarketLineItemNo("2");
		item.assignShipmentId(9L);

		assertThat(item.getMarketLineItemNo()).isEqualTo("2");
		assertThat(item.getShipmentId()).isEqualTo(9L);
	}
}
