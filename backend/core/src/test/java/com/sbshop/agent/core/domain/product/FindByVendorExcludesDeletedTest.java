package com.sbshop.agent.core.domain.product;

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
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:findbyvendordeleted;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = FindByVendorExcludesDeletedTest.TestApp.class)
class FindByVendorExcludesDeletedTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.product")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Autowired
	private ProductRepository productRepository;

	@Test
	@DisplayName("D-290: 삭제된 상품은 소싱처별 대상에서 뺀다 — 폐기한 상품을 매일 다시 크롤하지 않는다")
	void deletedProductsAreExcluded() {
		Product alive = persist("SB-V1", VendorType.OCD);
		Product deleted = persist("SB-V2", VendorType.OCD);
		deleted.markDeleted();
		em.flush();

		List<Product> found = productRepository.findByVendor(VendorType.OCD);

		assertThat(found).extracting(Product::getId).contains(alive.getId());
		assertThat(found).extracting(Product::getId).doesNotContain(deleted.getId());
	}

	@Test
	@DisplayName("D-290: 살아 있는 상품은 그대로 나온다 — 필터가 대상을 통째로 지우지 않는다")
	void aliveProductsRemain() {
		Product a = persist("SB-V3", VendorType.COK);
		Product b = persist("SB-V4", VendorType.COK);

		List<Product> found = productRepository.findByVendor(VendorType.COK);

		assertThat(found).extracting(Product::getId).containsExactlyInAnyOrder(a.getId(), b.getId());
	}

	private Product persist(String sbCode, VendorType vendor) {
		Product product = Product.create(sbCode, new ProductCreateCommand(
			"https://example.com/p/1", new BigDecimal("25"), "n", "Some Product Name", "Some", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, BigDecimal.TEN, vendor, null));
		em.persist(product);
		em.flush();
		return product;
	}
}
