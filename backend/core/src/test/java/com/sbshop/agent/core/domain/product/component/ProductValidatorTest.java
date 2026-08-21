package com.sbshop.agent.core.domain.product.component;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductValidatorTest {
	private final ProductValidator validator = new ProductValidator();

	@Test
	@DisplayName("모든 필수 필드가 있으면 검증 통과")
	void validateForPublish_allFieldsPresent_passes() {
		Product product = createValidProduct();
		validator.validateForPublish(product);
	}

	@Test
	@DisplayName("이미지가 없으면 검증 실패")
	void validateForPublish_noImages_throwsException() {
		Product product = Product.create("SKU001", new ProductCreateCommand(
			"url", BigDecimal.TEN, "base", "orig", "brand", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET,
			List.of(), List.of(), "html", "비타민",
			true, 1, BigDecimal.ZERO, VendorType.IHB));
		assertThatThrownBy(() -> validator.validateForPublish(product))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("이미지");
	}

	@Test
	@DisplayName("판매가가 0 이하면 검증 실패")
	void validateForPublish_zeroPrice_throwsException() {
		Product product = Product.create("SKU001", new ProductCreateCommand(
			"url", BigDecimal.ZERO, "base", "orig", "brand", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET,
			List.of("img"), List.of("hosted"), "html", "비타민",
			true, 1, BigDecimal.ZERO, VendorType.IHB));
		assertThatThrownBy(() -> validator.validateForPublish(product))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("판매가");
	}

	private Product createValidProduct() {
		return Product.create("SKU001", new ProductCreateCommand(
			"url", new BigDecimal("25"), "base", "orig", "brand", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET,
			List.of("src"), List.of("hosted"), "html", "비타민",
			true, 1, new BigDecimal("20"), VendorType.IHB));
	}
}
