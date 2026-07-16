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

/**
 * F-PSRC-13 / R3 멱등성 하드가드.
 * <p>
 * 마켓 게시 재호출(동시 재호출 포함)로 같은 (product_id, market_type) 조합의
 * MarketRegistration이 <b>중복 생성</b>되는 것을 DB 레벨에서 막는다.
 * {@link com.sbshop.agent.core.application.product.MarketRegistrationTxService#savePending}의
 * findByProductIdAndMarketType 재사용은 순차 재호출에는 멱등이지만, 두 트랜잭션이 동시에
 * "행 없음"을 관측하면 둘 다 insert하는 경쟁이 남는다. 이 경쟁을 유니크 제약으로 하드 차단한다.
 * <p>
 * 이 테스트는 같은 product+market으로 두 행을 flush하면 제약 위반이 발생함을 검증한다.
 * (유니크 제약이 없으면 두 insert가 모두 성공해 실패한다 — Red.)
 */
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

		// GenerationType.IDENTITY 엔티티는 persist 시점에 즉시 INSERT되어 ID를 얻으므로,
		// 유니크 제약 위반이 persist(+flush) 경로에서 표면화된다.
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
