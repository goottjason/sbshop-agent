package com.sbshop.agent.api.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.product.ProductPricePreviewUseCase;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductPricePreviewControllerTest {
	private final ProductPricePreviewUseCase preview = mock(ProductPricePreviewUseCase.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProductPricePreviewController(preview))
		.setControllerAdvice(new GlobalExceptionHandler()).build();

	@Test
	void exposesReadOnlyPriceExplanationWithExactDecimalStrings() throws Exception {
		when(preview.preview(1L)).thenReturn(new ProductPricePreviewUseCase.Response("READ_ONLY", 1L, "SB1", Instant.now(),
			List.of(new ProductPricePreviewUseCase.Item(MarketType.COUPANG, ProductPricePreviewUseCase.Status.CALCULATED,
				"12300", "12340", "12400", true, "최소마진 보장"))));
		mvc.perform(get("/api/v1/products/1/price-preview")).andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("READ_ONLY"))
			.andExpect(jsonPath("$.items[0].roundedPrice").value("12300"))
			.andExpect(jsonPath("$.items[0].minimumPrice").value("12340"))
			.andExpect(jsonPath("$.items[0].salePrice").value("12400"))
			.andExpect(jsonPath("$.items[0].minimumAdjusted").value(true));
	}

	@Test
	void missingProductReturnsNotFound() throws Exception {
		when(preview.preview(1L)).thenThrow(new ResourceNotFoundException("상품 없음"));
		mvc.perform(get("/api/v1/products/1/price-preview")).andExpect(status().isNotFound());
	}
}
