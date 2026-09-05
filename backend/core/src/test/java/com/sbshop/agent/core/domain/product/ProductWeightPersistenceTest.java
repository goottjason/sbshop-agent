package com.sbshop.agent.core.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.ProductWeight;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:productweight;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductWeightPersistenceTest.TestApp.class)
class ProductWeightPersistenceTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = ProductRepository.class)
	static class TestApp {}

	@Autowired
	private ProductRepository repository;
	@Autowired
	private EntityManager entityManager;

	@Test
	void gramsKeepTheirPrecisionAfterDatabaseRoundTripAndUpdate() {
		Product product = repository
			.saveAndFlush(Product.create("SB-KG", command(ProductWeight.fromGrams(new BigDecimal("125.01")))));
		Long id = product.getId();
		entityManager.clear();
		Product reloaded = repository.findById(id).orElseThrow();
		assertThat(reloaded.getLogisticsInfo().getWeight()).isEqualByComparingTo("0.12501");
		reloaded.update(ProductUpdateCommand.builder().weight(new BigDecimal("0.00001")).build());
		repository.flush();
		entityManager.clear();
		assertThat(repository.findById(id).orElseThrow().getLogisticsInfo().getWeight())
			.isEqualByComparingTo("0.00001");
	}

	@ParameterizedTest
	@ValueSource(strings = {"100", "99999999.99"})
	void existingValuesAreNotRescaledOrNarrowedOnRead(String weight) {
		Product product = repository.saveAndFlush(Product.create("SB-LEGACY", command(null)));
		Long id = product.getId();
		entityManager.createNativeQuery("update sb_product set weight = :weight where id = :id")
			.setParameter("weight", new BigDecimal(weight)).setParameter("id", id).executeUpdate();
		entityManager.clear();
		assertThat(repository.findById(id).orElseThrow().getLogisticsInfo().getWeight()).isEqualByComparingTo(weight);
	}

	@ParameterizedTest
	@ValueSource(strings = {"-0.001", "0.000001", "100000000"})
	void createAndUpdateRejectLossyOrOutOfRangeWeight(String weight) {
		assertThatThrownBy(() -> command(new BigDecimal(weight))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProductUpdateCommand.builder().weight(new BigDecimal(weight)).build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	private ProductCreateCommand command(BigDecimal kg) {
		return new ProductCreateCommand("https://example.com/item", BigDecimal.TEN, "상품", "Product",
			"Brand", "US", kg, BigDecimal.ONE, MeasureUnit.EA, List.of(), List.of(), "", "", true,
			1, new BigDecimal("20"), VendorType.IHB, null);
	}
}
