package com.sbshop.agent.core.domain.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:productsearch;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductSearchSpecificationTest.TestApp.class)
class ProductSearchSpecificationTest {

	private static final Pageable FIRST_PAGE = PageRequest.of(0, 50);

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = ProductRepository.class)
	static class TestApp {}

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("categories 단독: 나열된 카테고리에 속한 상품만 반환한다")
	void categoriesOnly() {
		save("SB001", ProductCategory.SUPPLEMENT);
		save("SB002", ProductCategory.FOOD);
		save("SB003", ProductCategory.COSMETICS);

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.categories(List.of(ProductCategory.SUPPLEMENT)).build()))
			.containsExactlyInAnyOrder("SB001");
		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.categories(List.of(ProductCategory.SUPPLEMENT, ProductCategory.FOOD)).build()))
			.containsExactlyInAnyOrder("SB001", "SB002");
	}

	@Test
	@DisplayName("vendors 단독: 나열된 매입처의 상품만 반환한다")
	void vendorsOnly() {
		saveWithVendor("SB001", VendorType.IHB);
		saveWithVendor("SB002", VendorType.AMZ);
		saveWithVendor("SB003", VendorType.FTN);

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.vendors(List.of(VendorType.AMZ, VendorType.FTN)).build()))
			.containsExactlyInAnyOrder("SB002", "SB003");
	}

	@Test
	@DisplayName("stockStatuses: stockStatus가 null이면 stock>0 여부로 IN_STOCK/OUT_OF_STOCK을 판정한다")
	void stockStatusesFallsBackToStockQuantity() {
		saveWithStock("NULL_POSITIVE", 5, null);
		saveWithStock("NULL_ZERO", 0, null);
		saveWithStock("COLUMN_OUT", 999, StockStatus.OUT_OF_STOCK);
		saveWithStock("COLUMN_IN", 0, StockStatus.IN_STOCK);

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.stockStatuses(List.of(StockStatus.IN_STOCK)).build()))
			.containsExactlyInAnyOrder("NULL_POSITIVE", "COLUMN_IN");
		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.stockStatuses(List.of(StockStatus.OUT_OF_STOCK)).build()))
			.containsExactlyInAnyOrder("NULL_ZERO", "COLUMN_OUT");
	}

	@Test
	@DisplayName("markets: 나열된 마켓 중 하나 이상에 등록된 상품이면 포함한다(ANY)")
	void marketsMatchesAny() {
		Product coupangOnly = save("SB001", ProductCategory.SUPPLEMENT);
		Product storeOnly = save("SB002", ProductCategory.SUPPLEMENT);
		save("SB003", ProductCategory.SUPPLEMENT);
		register(coupangOnly, MarketType.COUPANG);
		register(storeOnly, MarketType.SMART_STORE);

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.markets(List.of(MarketType.COUPANG, MarketType.SMART_STORE)).build()))
			.containsExactlyInAnyOrder("SB001", "SB002");
		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.markets(List.of(MarketType.COUPANG)).build()))
			.containsExactlyInAnyOrder("SB001");
	}

	@Test
	@DisplayName("markets=[GMARKET]: CAFE24 등록행에 gmarket_goodsNo가 있는 상품만 매치한다(배지 파생 조건과 동일)")
	void gmarketMatchesCafe24IdentifierHolders() {
		Product linked = save("SB001", ProductCategory.SUPPLEMENT);
		Product bare = save("SB002", ProductCategory.SUPPLEMENT);
		register(linked, MarketType.CAFE24,
			"{\"product_no\": \"21367\", \"gmarket_goodsNo\": \"3315930979\"}");
		register(bare, MarketType.CAFE24, "{\"product_no\": \"14972\"}");

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.markets(List.of(MarketType.GMARKET)).build()))
			.containsExactlyInAnyOrder("SB001");
	}

	@Test
	@DisplayName("markets=[AUCTION]: auction_goodsNo 보유 CAFE24 상품만 매치하고 G마켓 전용 상품은 제외한다")
	void auctionMatchesOwnIdentifierOnly() {
		Product auctionLinked = save("SB001", ProductCategory.SUPPLEMENT);
		Product gmarketOnly = save("SB002", ProductCategory.SUPPLEMENT);
		register(auctionLinked, MarketType.CAFE24, "{\"auction_goodsNo\": \"D727738571\"}");
		register(gmarketOnly, MarketType.CAFE24, "{\"gmarket_goodsNo\": \"3490202556\"}");

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.markets(List.of(MarketType.AUCTION)).build()))
			.containsExactlyInAnyOrder("SB001");
	}

	@Test
	@DisplayName("markets=[COUPANG, GMARKET]: 등록행 기반 마켓과 식별자 파생 마켓을 ANY로 섞어 매치한다")
	void marketsMixRegistrationAndDerivedTypes() {
		Product coupang = save("SB001", ProductCategory.SUPPLEMENT);
		Product gmarket = save("SB002", ProductCategory.SUPPLEMENT);
		Product neither = save("SB003", ProductCategory.SUPPLEMENT);
		register(coupang, MarketType.COUPANG);
		register(gmarket, MarketType.CAFE24, "{\"gmarket_goodsNo\": \"3490202556\"}");
		register(neither, MarketType.CAFE24, "{\"product_no\": \"14972\"}");

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.markets(List.of(MarketType.COUPANG, MarketType.GMARKET)).build()))
			.containsExactlyInAnyOrder("SB001", "SB002");
	}

	@Test
	@DisplayName("markets=[CAFE24]: 카페24 자체는 식별자와 무관하게 등록행 존재로 매치한다")
	void cafe24MatchesByRegistrationRegardlessOfIdentifiers() {
		Product bare = save("SB001", ProductCategory.SUPPLEMENT);
		save("SB002", ProductCategory.SUPPLEMENT);
		register(bare, MarketType.CAFE24, "{\"product_no\": \"14972\"}");

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.markets(List.of(MarketType.CAFE24)).build()))
			.containsExactlyInAnyOrder("SB001");
	}

	@Test
	@DisplayName("markets=[GMARKET]: 식별자 키가 있어도 값이 비어 있으면 제외한다(배지가 링크를 만들지 않는 것과 동일)")
	void gmarketExcludesEmptyIdentifierValue() {
		Product emptySpaced = save("SB001", ProductCategory.SUPPLEMENT);
		Product emptyCompact = save("SB002", ProductCategory.SUPPLEMENT);
		Product real = save("SB003", ProductCategory.SUPPLEMENT);
		register(emptySpaced, MarketType.CAFE24, "{\"gmarket_goodsNo\": \"\"}");
		register(emptyCompact, MarketType.CAFE24, "{\"gmarket_goodsNo\":\"\"}");
		register(real, MarketType.CAFE24, "{\"gmarket_goodsNo\": \"3315930979\"}");

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.markets(List.of(MarketType.GMARKET)).build()))
			.containsExactlyInAnyOrder("SB003");
	}

	@Test
	@DisplayName("markets=[GMARKET]: 운영 저장 형태(콜론 뒤 공백)와 컴팩트 형태를 모두 매치한다")
	void gmarketMatchesBothSerializationShapes() {
		Product spaced = save("SB001", ProductCategory.SUPPLEMENT);
		Product compact = save("SB002", ProductCategory.SUPPLEMENT);
		register(spaced, MarketType.CAFE24,
			"{\"product_no\": \"21367\", \"gmarket_goodsNo\": \"3315930979\"}");
		register(compact, MarketType.CAFE24,
			"{\"product_no\":\"21367\",\"gmarket_goodsNo\":\"3315930979\"}");

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.markets(List.of(MarketType.GMARKET)).build()))
			.containsExactlyInAnyOrder("SB001", "SB002");
	}

	@Test
	@DisplayName("inStockOnly: true면 재고 수량이 0보다 큰 상품만 반환한다")
	void inStockOnlyFiltersZeroStock() {
		saveWithStock("HAS_STOCK", 5, StockStatus.IN_STOCK);
		saveWithStock("NO_STOCK", 0, StockStatus.IN_STOCK);

		assertThat(sbCodesOf(ProductSearchCondition.builder().inStockOnly(true).build()))
			.containsExactlyInAnyOrder("HAS_STOCK");
		assertThat(sbCodesOf(ProductSearchCondition.builder().inStockOnly(false).build()))
			.containsExactlyInAnyOrder("HAS_STOCK", "NO_STOCK");
	}

	@Test
	@DisplayName("keyword + categories 조합: 두 조건을 AND로 함께 적용한다")
	void keywordAndCategoriesCombine() {
		saveNamed("SB001", "비타민C 1000mg", ProductCategory.SUPPLEMENT);
		saveNamed("SB002", "비타민C 젤리", ProductCategory.FOOD);
		saveNamed("SB003", "오메가3", ProductCategory.SUPPLEMENT);

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.keyword("비타민")
			.categories(List.of(ProductCategory.SUPPLEMENT))
			.build()))
			.containsExactlyInAnyOrder("SB001");
	}

	@Test
	@DisplayName("조건이 전혀 없으면 전건을 반환한다(기존 동작 불변)")
	void emptyConditionReturnsAll() {
		save("SB001", ProductCategory.SUPPLEMENT);
		save("SB002", ProductCategory.FOOD);
		save("SB003", ProductCategory.UNKNOWN);

		assertThat(sbCodesOf(ProductSearchCondition.none()))
			.containsExactlyInAnyOrder("SB001", "SB002", "SB003");
	}

	@Test
	@DisplayName("marketFilter: registered=true는 등록 상품만, false는 미등록 상품만 반환한다(기존 동작 보존)")
	void marketFilterKeepsRegisteredSemantics() {
		Product registered = save("SB001", ProductCategory.SUPPLEMENT);
		save("SB002", ProductCategory.SUPPLEMENT);
		register(registered, MarketType.COUPANG);

		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.marketFilterType(MarketType.COUPANG).marketFilterRegistered(true).build()))
			.containsExactlyInAnyOrder("SB001");
		assertThat(sbCodesOf(ProductSearchCondition.builder()
			.marketFilterType(MarketType.COUPANG).marketFilterRegistered(false).build()))
			.containsExactlyInAnyOrder("SB002");
	}

	@Test
	@DisplayName("keyword는 상품명·SB코드·브랜드를 대소문자 무시로 검색한다(기존 동작 보존)")
	void keywordSearchesNameCodeAndBrand() {
		saveNamed("SB001", "Vitamin C", ProductCategory.SUPPLEMENT);
		saveNamed("SB002", "오메가3", ProductCategory.SUPPLEMENT);

		assertThat(sbCodesOf(ProductSearchCondition.builder().keyword("vitamin").build()))
			.containsExactlyInAnyOrder("SB001");
		assertThat(sbCodesOf(ProductSearchCondition.builder().keyword("sb00").build()))
			.containsExactlyInAnyOrder("SB001", "SB002");
		assertThat(sbCodesOf(ProductSearchCondition.builder().keyword("테스트브랜드").build()))
			.containsExactlyInAnyOrder("SB001", "SB002");
	}

	@Test
	@DisplayName("findDistinctCategories: 실존하는 비null 카테고리를 중복 없이 반환한다")
	void findDistinctCategoriesReturnsExistingOnly() {
		save("SB001", ProductCategory.SUPPLEMENT);
		save("SB002", ProductCategory.SUPPLEMENT);
		save("SB003", ProductCategory.FOOD);
		save("SB004", ProductCategory.UNKNOWN);
		clearCategory("SB004");

		assertThat(productRepository.findDistinctCategories())
			.containsExactlyInAnyOrder(ProductCategory.SUPPLEMENT, ProductCategory.FOOD);
	}

	private List<String> sbCodesOf(ProductSearchCondition condition) {
		Page<Product> page = productRepository.findAll(ProductSpecifications.matching(condition), FIRST_PAGE);
		return page.getContent().stream().map(Product::getSbCode).toList();
	}

	private Product save(String sbCode, ProductCategory category) {
		return persist(sbCode, ProductUpdateCommand.builder().category(category).build(), StockStatus.IN_STOCK);
	}

	private Product saveNamed(String sbCode, String name, ProductCategory category) {
		return persist(sbCode, ProductUpdateCommand.builder().category(category).name(name).build(),
			StockStatus.IN_STOCK);
	}

	private Product saveWithVendor(String sbCode, VendorType vendor) {
		return persist(sbCode, ProductUpdateCommand.builder().vendor(vendor).build(), StockStatus.IN_STOCK);
	}

	private Product saveWithStock(String sbCode, Integer stock, StockStatus stockStatus) {
		return persist(sbCode, ProductUpdateCommand.builder().stock(stock).build(), stockStatus);
	}

	private Product persist(String sbCode, ProductUpdateCommand command, StockStatus stockStatus) {
		Product product = Product.create(sbCode, new ProductCreateCommand(
			"http://source/" + sbCode, BigDecimal.TEN, "기본명", "original", "테스트브랜드", "KR",
			BigDecimal.ONE, BigDecimal.ONE, MeasureUnit.UNKNOWN, List.of(), List.of(),
			"", null, true, 1, BigDecimal.ZERO, VendorType.IHB));
		product.update(command);
		product.updateStockStatus(stockStatus);
		Product saved = productRepository.saveAndFlush(product);
		entityManager.clear();
		return saved;
	}

	private void clearCategory(String sbCode) {
		entityManager.createNativeQuery("UPDATE sb_product SET category = NULL WHERE sb_code = :sbCode")
			.setParameter("sbCode", sbCode)
			.executeUpdate();
		entityManager.clear();
	}

	private void register(Product product, MarketType marketType) {
		register(product, marketType, "{}");
	}

	private void register(Product product, MarketType marketType, String identifiersJson) {
		entityManager.persist(MarketRegistration.builder()
			.productId(product.getId())
			.sbProductId(product.getId())
			.marketType(marketType)
			.marketProductName("테스트 상품")
			.marketIdentifiers(identifiersJson)
			.marketDetailedInfo("{}")
			.build());
		entityManager.flush();
	}
}
