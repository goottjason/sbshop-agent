package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProductControllerFilterParamTest {

	@Mock
	private ProductSearchUseCase productSearchUseCase;
	@Mock
	private ProductManageUseCase productManageUseCase;
	@Mock
	private ImageDownloadClient imageDownloadClient;
	@Mock
	private ProductInfoCrawlerPort productInfoCrawlerPort;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private ActionLogService actionLogService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(
			productSearchUseCase, productManageUseCase, imageDownloadClient,
			productInfoCrawlerPort, marketRegistrationRepository, actionLogService))
			.setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
			.build();
	}

	@Test
	@DisplayName("GET /api/v1/products: 신규 필터 파라미터가 검색 조건으로 그대로 전달된다")
	void newFilterParamsReachSearchCondition() throws Exception {
		stubEmptyPage();

		mockMvc.perform(get("/api/v1/products")
			.param("keyword", "비타민")
			.param("categories", "SUPPLEMENT", "FOOD")
			.param("vendors", "IHB")
			.param("stockStatuses", "IN_STOCK")
			.param("markets", "COUPANG", "SMART_STORE")
			.param("inStockOnly", "true"))
			.andExpect(status().isOk());

		ProductSearchCondition condition = captureCondition();
		assertThat(condition.keyword()).isEqualTo("비타민");
		assertThat(condition.categories())
			.containsExactly(ProductCategory.SUPPLEMENT, ProductCategory.FOOD);
		assertThat(condition.vendors()).containsExactly(VendorType.IHB);
		assertThat(condition.stockStatuses()).containsExactly(StockStatus.IN_STOCK);
		assertThat(condition.markets())
			.containsExactly(MarketType.COUPANG, MarketType.SMART_STORE);
		assertThat(condition.inStockOnly()).isTrue();
	}

	@Test
	@DisplayName("GET /api/v1/products: includeUncategorized=true가 검색 조건으로 전달된다")
	void includeUncategorizedParamReachesSearchCondition() throws Exception {
		stubEmptyPage();

		mockMvc.perform(get("/api/v1/products")
			.param("categories", "FOOD")
			.param("includeUncategorized", "true"))
			.andExpect(status().isOk());

		ProductSearchCondition condition = captureCondition();
		assertThat(condition.categories()).containsExactly(ProductCategory.FOOD);
		assertThat(condition.includeUncategorized()).isTrue();
	}

	@Test
	@DisplayName("GET /api/v1/products: includeUncategorized 미전송 시 기본값 false다")
	void includeUncategorizedDefaultsToFalse() throws Exception {
		stubEmptyPage();

		mockMvc.perform(get("/api/v1/products").param("categories", "FOOD"))
			.andExpect(status().isOk());

		assertThat(captureCondition().includeUncategorized()).isFalse();
	}

	@Test
	@DisplayName("GET /api/v1/products: 파라미터가 하나도 없으면 빈 조건 + 기본 페이지 크기 50으로 조회한다")
	void noParamsKeepsLegacyBehaviour() throws Exception {
		stubEmptyPage();

		mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());

		ArgumentCaptor<ProductSearchCondition> conditionCaptor = ArgumentCaptor.forClass(ProductSearchCondition.class);
		ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
		verify(productSearchUseCase).searchProducts(conditionCaptor.capture(), pageableCaptor.capture());

		ProductSearchCondition condition = conditionCaptor.getValue();
		assertThat(condition.keyword()).isNull();
		assertThat(condition.marketFilterType()).isNull();
		assertThat(condition.categories()).isEmpty();
		assertThat(condition.vendors()).isEmpty();
		assertThat(condition.stockStatuses()).isEmpty();
		assertThat(condition.markets()).isEmpty();
		assertThat(condition.inStockOnly()).isFalse();
		assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
	}

	@Test
	@DisplayName("GET /api/v1/products: marketFilter의 등록/미등록(!) 의미가 조건 객체로 보존된다")
	void marketFilterMapsToRegisteredFlag() throws Exception {
		stubEmptyPage();

		mockMvc.perform(get("/api/v1/products").param("marketFilter", "coupang"))
			.andExpect(status().isOk());
		ProductSearchCondition registered = captureCondition();
		assertThat(registered.marketFilterType()).isEqualTo(MarketType.COUPANG);
		assertThat(registered.marketFilterRegistered()).isTrue();
	}

	@Test
	@DisplayName("GET /api/v1/products: marketFilter=!COUPANG는 미등록 조건으로 전달된다")
	void unregisteredMarketFilterMapsToFalse() throws Exception {
		stubEmptyPage();

		mockMvc.perform(get("/api/v1/products").param("marketFilter", "!COUPANG"))
			.andExpect(status().isOk());
		ProductSearchCondition unregistered = captureCondition();
		assertThat(unregistered.marketFilterType()).isEqualTo(MarketType.COUPANG);
		assertThat(unregistered.marketFilterRegistered()).isFalse();
	}

	@Test
	@DisplayName("GET /api/v1/products/categories: 실존 카테고리 이름 배열을 200으로 반환한다")
	void categoriesEndpointReturnsNames() throws Exception {
		when(productSearchUseCase.getCategoryNames())
			.thenReturn(List.of("COSMETICS", "FOOD", "SUPPLEMENT"));

		mockMvc.perform(get("/api/v1/products/categories"))
			.andExpect(status().isOk())
			.andExpect(content().json("[\"COSMETICS\",\"FOOD\",\"SUPPLEMENT\"]"));
	}

	private void stubEmptyPage() {
		Page<Product> empty = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(empty);
	}

	private ProductSearchCondition captureCondition() {
		ArgumentCaptor<ProductSearchCondition> captor = ArgumentCaptor.forClass(ProductSearchCondition.class);
		verify(productSearchUseCase).searchProducts(captor.capture(), any());
		return captor.getValue();
	}
}
