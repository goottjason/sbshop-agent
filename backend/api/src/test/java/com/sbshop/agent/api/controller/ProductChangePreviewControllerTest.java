package com.sbshop.agent.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sbshop.agent.core.application.product.edit.ProductNumericPreviewUseCase;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductChangePreviewControllerTest {
	private final ProductNumericPreviewUseCase preview = mock(ProductNumericPreviewUseCase.class);
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ProductChangePreviewController(preview)).build();
	}

	@Test
	void validDecimalStringRequestIsReadOnly() throws Exception {
		when(preview.preview(any())).thenReturn(new ProductNumericPreviewUseCase.Response("READ_ONLY", Instant.now(),
			0, 0, 0, 0, 0, List.of()));
		mvc.perform(post("/api/v1/products/changes/numeric-preview").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"productIds":[1],"changes":[{"field":"SALE_PRICE","operation":"ADD","value":"-100.00"}]}
				"""))
			.andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("READ_ONLY"));
		verify(preview).preview(any());
	}

	@ParameterizedTest
	@CsvSource({"12345,12300", "12650,12700"})
	void defaultPolicyReturnsExactBeforeRawAndRoundedValues(String price, String rounded) throws Exception {
		ProductReader reader = mock(ProductReader.class);
		MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
		Product product = mock(Product.class);
		when(product.getId()).thenReturn(1L);
		when(product.getSbCode()).thenReturn("SB1");
		when(product.getPriceInfo()).thenReturn(PriceInfo.builder().salePrice(new BigDecimal("10000")).build());
		when(product.getLogisticsInfo()).thenReturn(LogisticsInfo.builder().stock(3).bundleQuantity(3).build());
		when(reader.findAllByIds(List.of(1L))).thenReturn(List.of(product));
		when(registrations.findByProductIdIn(List.of(1L))).thenReturn(List.of());
		MockMvc realPreviewMvc = MockMvcBuilders.standaloneSetup(new ProductChangePreviewController(
			new ProductNumericPreviewUseCase(reader, registrations))).build();
		realPreviewMvc.perform(post("/api/v1/products/changes/numeric-preview").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"productIds":[1],"changes":[
				{"field":"SALE_PRICE","operation":"SET","value":"%s"},
				{"field":"STOCK","operation":"PERCENT","value":"50"},
				{"field":"BUNDLE_QUANTITY","operation":"PERCENT","value":"50"}]}
				""".formatted(price)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("READ_ONLY"))
			.andExpect(jsonPath("$.valid").value(1))
			.andExpect(jsonPath("$.items[0].fields[0].before").value("10000"))
			.andExpect(jsonPath("$.items[0].fields[0].calculated").value(price))
			.andExpect(jsonPath("$.items[0].fields[0].after").value(rounded))
			.andExpect(jsonPath("$.items[0].fields[0].rounded").value(true))
			.andExpect(jsonPath("$.items[0].fields[0].reason").value("100원 단위 반올림"))
			.andExpect(jsonPath("$.items[0].fields[1].calculated").value("4.5"))
			.andExpect(jsonPath("$.items[0].fields[1].after").value("4"))
			.andExpect(jsonPath("$.items[0].fields[2].calculated").value("4.5"))
			.andExpect(jsonPath("$.items[0].fields[2].after").value("4"));
		verify(product, never()).update(any());
		verify(registrations, never()).save(any());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{}",
		"{\"productIds\":[1],\"changes\":[{\"field\":\"SALE_PRICE\",\"operation\":\"SET\",\"value\":12345}],\"fractionPolicy\":\"ROUND\"}",
		"{\"productIds\":[1],\"changes\":[{\"field\":\"UNKNOWN_FIELD\",\"operation\":\"SET\",\"value\":1}]}",
		"{\"productIds\":[1],\"changes\":[{\"field\":\"STOCK\",\"operation\":\"SET\",\"value\":\"wrong\"}]}",
		"{\"productIds\":[1],\"changes\":[{\"field\":\"MARGIN_RATE\",\"operation\":\"PERCENT\",\"value\":1}]}",
		"{\"productIds\":[0],\"changes\":[{\"field\":\"STOCK\",\"operation\":\"SET\",\"value\":1}]}"
	})
	void malformedRequestIsBadRequestNotServerError(String json) throws Exception {
		mvc.perform(
			post("/api/v1/products/changes/numeric-preview").contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(preview);
	}
}
