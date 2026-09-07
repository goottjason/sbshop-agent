package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.market.marketplus.MarketPlusTransmissionService;
import com.sbshop.agent.core.application.product.*;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.dto.*;
import com.sbshop.agent.core.domain.product.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductContentFreshnessControllerTest {
	ProductSearchUseCase searches = mock(ProductSearchUseCase.class);
	MockMvc mvc;

	@BeforeEach
	void before() {
		var controller = new ProductController(searches, mock(ProductManageUseCase.class),
			mock(ImageDownloadClient.class),
			mock(ProductInfoCrawlerPort.class), mock(MarketRegistrationRepository.class), mock(ActionLogService.class),
			mock(MarketPlusTransmissionService.class));
		mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(
				new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
			.build();
		when(searches.searchProducts(any(), any())).thenReturn(Page.empty(PageRequest.of(0, 50)));
	}

	@Test
	void getParametersReachTheConditionAndDefaultSortIsWorkspacePriority() throws Exception {
		mvc.perform(get("/api/v1/products").param("contentAgeDays", "90").param("contentAgeField", "IMAGES"))
			.andExpect(status().isOk());
		var condition = ArgumentCaptor.forClass(ProductSearchCondition.class);
		var pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(searches).searchProducts(condition.capture(), pageable.capture());
		assertThat(condition.getValue().contentAgeDays()).isEqualTo(90);
		assertThat(condition.getValue().contentAgeField()).isEqualTo(ProductContentAgeField.IMAGES);
		assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by("workspacePriority"));
	}

	@Test
	void postKeepsNullAgeUnfilteredAndSupportsExplicitContentSort() throws Exception {
		mvc.perform(
			post("/api/v1/products/search").param("sort", "contentOldest,asc").contentType(MediaType.APPLICATION_JSON)
				.content("{\"contentAgeDays\":null,\"contentAgeField\":\"DETAIL_HTML\"}"))
			.andExpect(status().isOk());
		verify(searches).searchProducts(
			argThat(condition -> condition.contentAgeDays() == null
				&& condition.contentAgeField() == ProductContentAgeField.DETAIL_HTML),
			argThat(page -> page.getSort().equals(Sort.by("contentOldest"))));
	}

	@Test
	void invalidAgesAndFieldNamesDoNotQueryProducts() throws Exception {
		for (String value : List.of("0", "-1", "36501"))
			mvc.perform(get("/api/v1/products").param("contentAgeDays", value)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/products").param("contentAgeField", "COLLECTED")).andExpect(status().isBadRequest());
		verify(searches, never()).searchProducts(any(), any());
	}

	@Test
	void listIncludesCollectedAndAppliedTimestampsSeparatelyWithOnePageAggregateLookup() throws Exception {
		var product = Product.create("SB-CONTENT",
			new ProductCreateCommand("https://kr.iherb.com/pr/example/123", new BigDecimal("10000"),
				"상품", "Original", "브랜드", "US", new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G,
				List.of(), List.of(), "본문", "FOOD", true, 3, new BigDecimal("20"), VendorType.IHB, null));
		org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 7L);
		when(searches.searchProducts(any(), any()))
			.thenReturn(new PageImpl<>(List.of(product), PageRequest.of(0, 50), 1));
		when(searches.getContentFreshness(List.of(7L)))
			.thenReturn(Map.of(7L, new ProductContentFreshness(Instant.parse("2026-09-07T12:00:00Z"),
				Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-09-07T12:00:00Z"), null)));
		mvc.perform(get("/api/v1/products")).andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].contentFreshness.imagesCollectedAt").value("2026-09-07T12:00:00Z"))
			.andExpect(jsonPath("$.content[0].contentFreshness.imagesAppliedAt").value("2026-01-01T12:00:00Z"))
			.andExpect(jsonPath("$.content[0].contentFreshness.detailHtmlAppliedAt").isEmpty());
		verify(searches).getContentFreshness(List.of(7L));
	}
}
