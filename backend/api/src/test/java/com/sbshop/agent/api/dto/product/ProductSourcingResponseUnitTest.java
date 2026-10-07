package com.sbshop.agent.api.dto.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductSourcingResponseUnitTest {
	@ParameterizedTest
	@CsvSource({"capsules, CAPSULE", "정, TABLET", "ml, ML", "g, G", "oz, OZ", ", UNKNOWN"})
	void exposesTheNormalizedUnitNeededToPreserveCapacityOnSave(String raw, String expected) {
		var response = ProductSourcingResponse.from(ScrapedProductDto.builder()
			.brand("브랜드").baseName("상품").capacity(new BigDecimal("60")).unit(raw).build());
		var json = new ObjectMapper().valueToTree(response);

		assertThat(json.path("measureUnit").asText()).isEqualTo(expected);
	}
}
