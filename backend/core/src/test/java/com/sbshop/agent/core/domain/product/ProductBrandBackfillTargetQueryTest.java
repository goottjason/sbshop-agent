package com.sbshop.agent.core.domain.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:brandbackfilltarget;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductBrandBackfillTargetQueryTest.TestApp.class)
class ProductBrandBackfillTargetQueryTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.product")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Autowired
	private ProductRepository productRepository;

	@Test
	@DisplayName("D-261: brand가 original_name의 첫 단어와 같은 상품만 대상으로 뽑는다")
	void findBrandBackfillTargetIds_selectsOnlyFirstWordMatches() {
		Product truncated = persist("SB-B1", "Nature's", "Nature's Way Chlorofresh Liquid", VendorType.IHB);
		Product full = persist("SB-B2", "Nature's Way", "Nature's Way Chlorofresh Liquid", VendorType.IHB);

		List<Long> targets = productRepository.findBrandBackfillTargetIds();

		assertThat(targets).contains(truncated.getId());
		assertThat(targets).doesNotContain(full.getId());
	}

	@Test
	@DisplayName("D-261: 소싱처를 지정하면 그 소싱처의 대상만 뽑는다")
	void findBrandBackfillTargetIds_filtersByVendor() {
		Product ihb = persist("SB-B3", "Solaray", "Solaray Black Currant Seed Oil", VendorType.IHB);
		Product vtb = persist("SB-B4", "Solaray", "Solaray Black Currant Seed Oil", VendorType.VTB);

		List<Long> targets = productRepository.findBrandBackfillTargetIds(VendorType.VTB);

		assertThat(targets).containsExactly(vtb.getId());
		assertThat(targets).doesNotContain(ihb.getId());
	}

	@Test
	@DisplayName("D-261: 소싱 URL이 없는 상품은 대상에서 제외한다")
	void findBrandBackfillTargetIds_excludesProductsWithoutSourceUrl() {
		Product withoutUrl = Product.create("SB-B5", new ProductCreateCommand(
			null, new BigDecimal("25"), "n", "Garden Primal Defense", "Garden", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, BigDecimal.TEN, VendorType.IHB, null));
		em.persist(withoutUrl);
		em.flush();

		List<Long> targets = productRepository.findBrandBackfillTargetIds();

		assertThat(targets).doesNotContain(withoutUrl.getId());
	}

	@Test
	@DisplayName("D-261: 단일 단어 상품명은 브랜드 전체가 이름과 같을 때만 대상이다")
	void findBrandBackfillTargetIds_singleWordName() {
		Product product = persist("SB-B6", "Chlorofresh", "Chlorofresh", VendorType.IHB);

		List<Long> targets = productRepository.findBrandBackfillTargetIds();

		assertThat(targets).contains(product.getId());
	}

	private Product persist(String sbCode, String brand, String originalName, VendorType vendor) {
		Product product = Product.create(sbCode, new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("25"), "n", originalName, brand, "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, BigDecimal.TEN, vendor, null));
		em.persist(product);
		em.flush();
		return product;
	}
}
