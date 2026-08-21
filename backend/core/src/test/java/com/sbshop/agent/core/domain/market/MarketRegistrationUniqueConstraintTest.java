package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = MarketRegistrationUniqueConstraintTest.TestApp.class)
class MarketRegistrationUniqueConstraintTest {

	@SpringBootApplication
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.dummy")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("같은 (product_id, market_type)로 두 등록행을 저장하면 유니크 제약 위반이 발생한다")
	void duplicateProductMarket_violatesUniqueConstraint() {
		Long productId = 100L;
		MarketType market = MarketType.COUPANG;

		em.persist(buildRegistration(productId, market));
		em.flush();

		MarketRegistration duplicate = buildRegistration(productId, market);
		assertThatThrownBy(() -> {
			em.persist(duplicate);
			em.flush();
		}).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	@DisplayName("다른 market_type이면 같은 product_id라도 각각 저장된다")
	void sameProductDifferentMarket_allowed() {
		Long productId = 200L;

		em.persist(buildRegistration(productId, MarketType.COUPANG));
		em.persist(buildRegistration(productId, MarketType.SMART_STORE));
		em.flush();
	}

	private MarketRegistration buildRegistration(Long productId, MarketType market) {
		return MarketRegistration.builder()
			.productId(productId)
			.sbProductId(productId)
			.marketType(market)
			.marketProductName("테스트 상품")
			.marketIdentifiers("{}")
			.marketDetailedInfo("{}")
			.build();
	}
}
