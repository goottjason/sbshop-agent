package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.api.dto.product.ProductListResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ProductControllerR6QueryTest {

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

	private ProductController controller() {
		return new ProductController(productSearchUseCase, productManageUseCase,
			imageDownloadClient, productInfoCrawlerPort, marketRegistrationRepository, actionLogService);
	}

	private void stubEmptyPage() {
		Page<Product> page = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(page);
	}

	private ProductSearchCondition captureCondition() {
		ArgumentCaptor<ProductSearchCondition> captor = ArgumentCaptor.forClass(ProductSearchCondition.class);
		verify(productSearchUseCase).searchProducts(captor.capture(), any());
		return captor.getValue();
	}

	private MarketRepublishResult noMarketResult() {
		return new MarketRepublishResult(List.of(), List.of(), Map.of());
	}

	@Test
	@DisplayName("getProducts: marketFilter와 keyword가 둘 다 오면 마켓 필터 AND 키워드 검색을 함께 적용한다")
	void getProducts_marketFilterAndKeyword_appliesBoth() {
		stubEmptyPage();

		ResponseEntity<Page<ProductListResponse>> res = controller().getProducts("shampoo", "COUPANG",
			null, null, null, null, false, PageRequest.of(0, 50));

		assertThat(res.getStatusCode().value()).isEqualTo(200);
		ProductSearchCondition condition = captureCondition();
		assertThat(condition.keyword()).isEqualTo("shampoo");
		assertThat(condition.marketFilterType()).isEqualTo(MarketType.COUPANG);
		assertThat(condition.marketFilterRegistered()).isTrue();
	}

	@Test
	@DisplayName("getProducts: marketFilter만 오면(키워드 없음) 키워드 없이 마켓 조건만 적용한다")
	void getProducts_marketFilterOnly_usesMarketSearch() {
		stubEmptyPage();

		controller().getProducts(null, "COUPANG", null, null, null, null, false, PageRequest.of(0, 50));

		ProductSearchCondition condition = captureCondition();
		assertThat(condition.keyword()).isNull();
		assertThat(condition.marketFilterType()).isEqualTo(MarketType.COUPANG);
		assertThat(condition.marketFilterRegistered()).isTrue();
	}

	@Test
	@DisplayName("getProducts: 미등록(!) 마켓 필터와 keyword가 둘 다 오면 registered=false로 결합 조회한다")
	void getProducts_unregisteredMarketFilterAndKeyword_appliesBoth() {
		stubEmptyPage();

		controller().getProducts("shampoo", "!COUPANG", null, null, null, null, false,
			PageRequest.of(0, 50));

		ProductSearchCondition condition = captureCondition();
		assertThat(condition.keyword()).isEqualTo("shampoo");
		assertThat(condition.marketFilterType()).isEqualTo(MarketType.COUPANG);
		assertThat(condition.marketFilterRegistered()).isFalse();
	}

	@Test
	@DisplayName("crawlSourceImages: 잘못된 형식 URL은 걸러내고 중복 URL은 순서 보존 제거한다")
	void crawlSourceImages_filtersInvalidAndDedups() {
		Product product = Mockito.mock(Product.class);
		when(productSearchUseCase.getProductDetail(7L)).thenReturn(product);
		when(product.getSourcingUrl()).thenReturn("http://src/7");
		when(productInfoCrawlerPort.crawlProductInfoAsDto("http://src/7"))
			.thenReturn(ScrapedProductDto.builder()
				.sourceImages(Arrays.asList(
					"http://img/a.jpg",
					"http://img/a.jpg",
					"not-a-url",
					"  ",
					null,
					"https://img/b.jpg"))
				.build());

		ResponseEntity<List<String>> res = controller().crawlSourceImages(7L);

		assertThat(res.getBody()).containsExactly("http://img/a.jpg", "https://img/b.jpg");
	}

	@Test
	@DisplayName("crawlAndUpload: 크롤 이미지가 상한을 초과하면 상한 개수만큼 절단해 다운로드한다")
	void crawlAndUpload_exceedsCap_truncates() {
		Product product = Mockito.mock(Product.class);
		when(productSearchUseCase.getProductDetail(7L)).thenReturn(product);
		when(product.getSourcingUrl()).thenReturn("http://src/7");

		int over = ProductController.MAX_CRAWL_IMAGES + 5;
		List<String> many = IntStream.range(0, over)
			.mapToObj(i -> "http://img/" + i + ".jpg")
			.toList();
		when(productInfoCrawlerPort.crawlProductInfoAsDto("http://src/7"))
			.thenReturn(ScrapedProductDto.builder().sourceImages(many).build());
		when(imageDownloadClient.downloadAndConvertDetailed(any()))
			.thenReturn(ImageProcessResult.of(List.of(), List.of()));
		when(productManageUseCase.updateImagesAndHtml(eq(7L), any())).thenReturn(noMarketResult());

		ResponseEntity<ImageUploadResponse> res = controller().crawlAndUpload(7L);

		assertThat(res.getStatusCode().value()).isEqualTo(200);
		@SuppressWarnings("unchecked") ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		verify(imageDownloadClient).downloadAndConvertDetailed(captor.capture());
		assertThat(captor.getValue()).hasSize(ProductController.MAX_CRAWL_IMAGES);
	}
}
