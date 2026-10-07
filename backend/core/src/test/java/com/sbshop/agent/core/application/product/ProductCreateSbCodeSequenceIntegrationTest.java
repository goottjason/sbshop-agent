package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:sbcode-sequence;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductCreateSbCodeSequenceIntegrationTest.TestApp.class)
class ProductCreateSbCodeSequenceIntegrationTest {
	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = ProductRepository.class)
	static class TestApp {}

	@Autowired
	private ProductRepository products;

	@Test
	void nextSequenceUsesTheLargestNumericSuffixAfterOneThousandProducts() {
		ProductCreateCommand command = new ProductCreateCommand("https://kr.iherb.com/pr/item/1",
			BigDecimal.TEN, "상품", "상품", "브랜드", "USA", BigDecimal.ONE, BigDecimal.ONE,
			MeasureUnit.EA, List.of(), List.of(), "상세", "supplements", true, 1,
			new BigDecimal("20"), VendorType.IHB, null);
		products.saveAllAndFlush(List.of(Product.create("261007IHB999", command),
			Product.create("261007IHB1000", command), Product.create("261007IHB1001", command),
			Product.create("261006IHB9999", command)));

		assertThat(products.findMaxSbCodeByPrefix("261007IHB")).contains("261007IHB1001");
	}
}
