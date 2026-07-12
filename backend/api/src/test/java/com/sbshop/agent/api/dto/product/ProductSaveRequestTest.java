package com.sbshop.agent.api.dto.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductSaveRequestTest {

	@Test
	@DisplayName("toCommand가 rawCategory를 command에 반영한다")
	void toCommandMapsRawCategory() {
		ProductSaveRequest req = new ProductSaveRequest(
			"https://iherb.com/x", new BigDecimal("10.5"), "베이스명", "Original", "브랜드",
			"미국", new BigDecimal("0.3"), new BigDecimal("500"), null,
			List.of("u0"), "<html>", "건강기능식품/비타민", true, 1,
			new BigDecimal("20"), VendorType.IHB);
		ProductCreateCommand cmd = req.toCommand();
		assertThat(cmd.rawCategory()).isEqualTo("건강기능식품/비타민");
	}
}
