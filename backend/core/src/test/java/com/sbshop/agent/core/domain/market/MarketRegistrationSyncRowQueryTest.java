package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationSyncRow;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:marketsyncrow;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketRegistrationSyncRowQueryTest.TestApp.class)
class MarketRegistrationSyncRowQueryTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.market.repository")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Autowired
	private MarketRegistrationRepository repository;

	@Test
	@DisplayName("대조용 projection이 SB코드·식별자를 한 번의 조인 질의로 가져온다")
	void syncRowsJoinProductSbCode() {
		Product product = persistProduct("SB-QUERY-1");
		persistRegistration(product.getId(), MarketType.COUPANG, "{\"sellerProductId\":\"9001\"}");

		List<MarketRegistrationSyncRow> rows = repository.findSyncRowsByMarketType(MarketType.COUPANG);

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getSbCode()).isEqualTo("SB-QUERY-1");
		assertThat(rows.get(0).getProductId()).isEqualTo(product.getId());
		assertThat(rows.get(0).getMarketIdentifiers()).contains("9001");
	}

	@Test
	@DisplayName("상품이 사라진 고아 등록행도 sbCode=null로 함께 반환한다(대조 대상에서 누락되지 않는다)")
	void orphanRegistrationIsReturnedWithNullSbCode() {
		persistRegistration(999_999L, MarketType.SMART_STORE, "{\"originProductNo\":\"1\"}");

		List<MarketRegistrationSyncRow> rows = repository.findSyncRowsByMarketType(MarketType.SMART_STORE);

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getSbCode()).isNull();
		assertThat(rows.get(0).getProductId()).isEqualTo(999_999L);
	}

	@Test
	@DisplayName("다른 마켓의 등록행은 반환하지 않는다")
	void filtersByMarketType() {
		Product product = persistProduct("SB-QUERY-2");
		persistRegistration(product.getId(), MarketType.CAFE24, "{\"product_no\":\"7\"}");

		assertThat(repository.findSyncRowsByMarketType(MarketType.ELEVEN_STREET)).isEmpty();
		assertThat(repository.findSyncRowsByMarketType(MarketType.CAFE24)).hasSize(1);
	}

	private Product persistProduct(String sbCode) {
		Product product = Product.create(sbCode, new ProductCreateCommand(
			null, null, "대조 테스트 상품", null, null, null, null, null, null, null, null, null, null,
			true, null, null, null));
		em.persist(product);
		em.flush();
		return product;
	}

	private void persistRegistration(Long productId, MarketType market, String identifiers) {
		em.persist(MarketRegistration.builder()
			.productId(productId)
			.sbProductId(productId)
			.marketType(market)
			.marketProductName("대조 테스트")
			.marketIdentifiers(identifiers)
			.marketDetailedInfo("{}")
			.build());
		em.flush();
		em.clear();
	}
}
