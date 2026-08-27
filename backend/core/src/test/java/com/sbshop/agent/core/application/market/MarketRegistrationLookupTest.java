package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
	"spring.datasource.url=jdbc:h2:mem:marketreglookup;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketRegistrationLookupTest.TestApp.class)
class MarketRegistrationLookupTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.market.repository")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Autowired
	private MarketRegistrationRepository repository;

	private MarketRegistrationLookup lookup;

	@BeforeEach
	void setUp() {
		lookup = new MarketRegistrationLookup(repository);
		repository.deleteAll();
		em.flush();
		em.clear();
	}

	@Test
	@DisplayName("운영 오배송 재현: product_no=20082는 230806IHB130으로 해석되고 200828TE001은 후보에서 제외된다")
	void resolvesCafe24ProductNoExactlyInsteadOfSubstringFirstHit() {
		seedProductionCollisionRows();

		Optional<MarketRegistration> resolved = lookup.findUnique(MarketType.CAFE24, "product_no", "20082");

		assertThat(resolved).isPresent();
		assertThat(resolved.get().identifier("custom_product_code")).isEqualTo("230806IHB130");
		assertThat(resolved.get().getSbProductId()).isEqualTo(2478L);
	}

	@Test
	@DisplayName("부분 문자열 전치 필터는 운영과 동일하게 7건을 긁어오지만 정확 일치는 1건뿐이다")
	void prefilterMatchesProductionCandidateCountButExactMatchIsSingle() {
		seedProductionCollisionRows();

		List<MarketRegistration> candidates = repository.findIdentifierCandidates(MarketType.CAFE24, "20082");

		assertThat(candidates).hasSize(7);
		assertThat(candidates.stream().filter(r -> "20082".equals(r.identifier("product_no"))).count()).isEqualTo(1L);
	}

	@Test
	@DisplayName("공백이 들어간 JSON 표기도 압축 표기와 동일하게 정확 일치한다")
	void matchesSpacedJsonIdentifiersIdenticallyToCompactOnes() {
		persist(9001L, MarketType.CAFE24, "{\"product_no\": \"31415\", \"custom_product_code\": \"SPACED\"}");
		persist(9002L, MarketType.CAFE24, "{\"product_no\":\"27182\",\"custom_product_code\":\"COMPACT\"}");

		assertThat(lookup.findUnique(MarketType.CAFE24, "product_no", "31415"))
			.get()
			.extracting(MarketRegistration::getSbProductId)
			.isEqualTo(9001L);
		assertThat(lookup.findUnique(MarketType.CAFE24, "product_no", "27182"))
			.get()
			.extracting(MarketRegistration::getSbProductId)
			.isEqualTo(9002L);
	}

	@Test
	@DisplayName("같은 키에 같은 값을 가진 등록행이 2건 이상이면 매칭하지 않는다")
	void refusesToGuessWhenTwoRegistrationsShareTheSameIdentifier() {
		persist(9101L, MarketType.CAFE24, "{\"product_no\":\"55555\"}");
		persist(9102L, MarketType.CAFE24, "{\"product_no\": \"55555\"}");

		assertThat(lookup.findUnique(MarketType.CAFE24, "product_no", "55555")).isEmpty();
	}

	@Test
	@DisplayName("일치하는 등록행이 없으면 비어 있는 결과를 준다")
	void returnsEmptyWhenNothingMatches() {
		persist(9201L, MarketType.CAFE24, "{\"product_no\":\"12345\"}");

		assertThat(lookup.findUnique(MarketType.CAFE24, "product_no", "99999")).isEmpty();
	}

	@Test
	@DisplayName("다른 키에 값이 들어 있어도 지정한 키가 아니면 매칭하지 않는다")
	void doesNotMatchValueFoundUnderAnotherKey() {
		persist(9301L, MarketType.CAFE24, "{\"product_no\":\"7850\",\"custom_product_code\":\"200828TE001\"}");

		assertThat(lookup.findUnique(MarketType.CAFE24, "product_no", "200828TE001")).isEmpty();
		assertThat(lookup.findUnique(MarketType.CAFE24, "custom_product_code", "200828TE001")).isPresent();
	}

	@Test
	@DisplayName("쿠팡 vendorItemId·sellerProductId는 각 키로 정확 일치한다")
	void resolvesCoupangKeysExactly() {
		persist(9401L, MarketType.COUPANG,
			"{\"externalVendorSku\":\"220227IHB019\",\"sellerProductId\":\"14813282340\","
				+ "\"vendorItemId\":\"89379435433\",\"barcode\":\"\",\"productId\":\"6432761632\"}");
		persist(9402L, MarketType.COUPANG,
			"{\"externalVendorSku\":\"220227IHB005\",\"sellerProductId\":\"148132823\","
				+ "\"vendorItemId\":\"89379434496\",\"barcode\":\"\"}");

		assertThat(lookup.findUnique(MarketType.COUPANG, "sellerProductId", "148132823"))
			.get()
			.extracting(MarketRegistration::getSbProductId)
			.isEqualTo(9402L);
		assertThat(lookup.findUnique(MarketType.COUPANG, "vendorItemId", "89379435433"))
			.get()
			.extracting(MarketRegistration::getSbProductId)
			.isEqualTo(9401L);
		assertThat(lookup.findUnique(MarketType.COUPANG, "vendorItemId", "6432761632")).isEmpty();
	}

	@Test
	@DisplayName("11번가 prdNo는 sellerPrdCd 안에 파묻힌 부분 문자열을 집지 않는다")
	void resolvesElevenstPrdNoExactly() {
		persist(9501L, MarketType.ELEVEN_STREET, "{\"prdNo\":\"3431019364\",\"sellerPrdCd\":\"210402IHB005\"}");
		persist(9502L, MarketType.ELEVEN_STREET, "{\"prdNo\":\"343101\",\"sellerPrdCd\":\"210524TE109\"}");

		assertThat(lookup.findUnique(MarketType.ELEVEN_STREET, "prdNo", "343101"))
			.get()
			.extracting(MarketRegistration::getSbProductId)
			.isEqualTo(9502L);
	}

	@Test
	@DisplayName("다른 마켓의 등록행은 같은 값이어도 매칭 대상이 아니다")
	void neverCrossesMarketBoundaries() {
		persist(9601L, MarketType.ELEVEN_STREET, "{\"prdNo\":\"20082\"}");

		assertThat(lookup.findUnique(MarketType.CAFE24, "product_no", "20082")).isEmpty();
	}

	@Test
	@DisplayName("값이 비었거나 키가 없으면 조회하지 않는다")
	void returnsEmptyForBlankInputs() {
		persist(9701L, MarketType.CAFE24, "{\"product_no\":\"12345\"}");

		assertThat(lookup.findUnique(MarketType.CAFE24, "product_no", null)).isEmpty();
		assertThat(lookup.findUnique(MarketType.CAFE24, "product_no", "  ")).isEmpty();
		assertThat(lookup.findUnique(MarketType.CAFE24, null, "12345")).isEmpty();
	}

	private void seedProductionCollisionRows() {
		persist(2320L, MarketType.CAFE24,
			"{\"product_no\":\"7850\",\"custom_product_code\":\"200828TE001\",\"product_code\":\"P0000LPY\"}");
		persist(2337L, MarketType.CAFE24,
			"{\"product_no\":\"7854\",\"custom_product_code\":\"200828TE005\",\"product_code\":\"P0000LQC\"}");
		persist(2416L, MarketType.CAFE24,
			"{\"product_no\":\"7857\",\"custom_product_code\":\"200828TE008\",\"product_code\":\"P0000LQF\"}");
		persist(2478L, MarketType.CAFE24,
			"{\"product_no\": \"20082\", \"product_code\": \"P000BDSK\", \"auction_goodsNo\": \"D888809556\", "
				+ "\"gmarket_goodsNo\": \"3490106960\", \"auction_masterNo\": \"3348685328\", "
				+ "\"gmarket_masterNo\": \"3348925289\", \"custom_product_code\": \"230806IHB130\"}");
		persist(3163L, MarketType.CAFE24,
			"{\"product_no\": \"7830\", \"product_code\": \"P0000LPE\", \"gmarket_goodsNo\": \"3490259594\", "
				+ "\"gmarket_masterNo\": \"3349309821\", \"custom_product_code\": \"200826UK015\"}");
		persist(3165L, MarketType.CAFE24,
			"{\"product_no\": \"7831\", \"product_code\": \"P0000LPF\", \"gmarket_goodsNo\": \"3490259568\", "
				+ "\"gmarket_masterNo\": \"3349309750\", \"custom_product_code\": \"200826UK016\"}");
		persist(3164L, MarketType.CAFE24,
			"{\"product_no\": \"7832\", \"product_code\": \"P0000LPG\", \"gmarket_goodsNo\": \"3490253578\", "
				+ "\"gmarket_masterNo\": \"3349293510\", \"custom_product_code\": \"200826UK017\"}");
	}

	private void persist(Long productId, MarketType market, String identifiers) {
		em.persist(MarketRegistration.builder()
			.productId(productId)
			.sbProductId(productId)
			.marketType(market)
			.marketProductName("매칭 테스트")
			.marketIdentifiers(identifiers)
			.marketDetailedInfo("{}")
			.build());
		em.flush();
		em.clear();
	}
}
