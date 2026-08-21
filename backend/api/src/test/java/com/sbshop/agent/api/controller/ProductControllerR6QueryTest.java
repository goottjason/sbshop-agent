package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

/**
 * R6 조회품질/입력검증:
 * <ul>
 *   <li>F-PROD-1: marketFilter와 keyword가 배타적이라 둘 다 주면 keyword가 무시되던 버그 →
 *       둘 다 주면 마켓 필터 AND 키워드 검색이 함께 적용돼야 한다.</li>
 *   <li>F-PROD-18: 크롤 이미지 URL에 유효성/중복 검증이 없어 잘못된 URL·중복 URL이 그대로 흘렀다 →
 *       http(s) 형식만 통과, 중복은 순서 보존 제거.</li>
 *   <li>F-PROD-22: 크롤 이미지 개수 상한이 없어 전량을 다운로드했다 →
 *       상한(MAX_CRAWL_IMAGES) 초과분은 절단(로그).</li>
 * </ul>
 */
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

	private MarketRepublishResult noMarketResult() {
		return new MarketRepublishResult(List.of(), List.of(), java.util.Map.of());
	}

	// ---- F-PROD-1: marketFilter + keyword 결합 ----

	@Test
	@DisplayName("getProducts: marketFilter와 keyword가 둘 다 오면 마켓 필터 AND 키워드 검색을 함께 적용한다")
	void getProducts_marketFilterAndKeyword_appliesBoth() {
		Page<Product> page = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
		when(productSearchUseCase.searchByMarketAndKeyword(
			eq(MarketType.COUPANG), eq(true), eq("shampoo"), any())).thenReturn(page);

		ResponseEntity<Page<ProductListResponse>> res = controller().getProducts("shampoo", "COUPANG",
			PageRequest.of(0, 50));

		assertThat(res.getStatusCode().value()).isEqualTo(200);
		// keyword가 무시되지 않고 결합 조회 메서드로 전달된다.
		verify(productSearchUseCase).searchByMarketAndKeyword(MarketType.COUPANG, true, "shampoo",
			PageRequest.of(0, 50));
		// 기존 배타 경로(마켓 단독/키워드 단독)는 호출되지 않는다.
		verify(productSearchUseCase, never()).searchByMarket(any(), anyBoolean(), any());
		verify(productSearchUseCase, never()).searchProducts(any(), any());
	}

	@Test
	@DisplayName("getProducts: marketFilter만 오면(키워드 없음) 기존 마켓 단독 조회를 사용한다")
	void getProducts_marketFilterOnly_usesMarketSearch() {
		Page<Product> page = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
		when(productSearchUseCase.searchByMarket(eq(MarketType.COUPANG), eq(true), any())).thenReturn(page);

		controller().getProducts(null, "COUPANG", PageRequest.of(0, 50));

		verify(productSearchUseCase).searchByMarket(MarketType.COUPANG, true, PageRequest.of(0, 50));
		verify(productSearchUseCase, never()).searchByMarketAndKeyword(any(), anyBoolean(), any(), any());
	}

	@Test
	@DisplayName("getProducts: 미등록(!) 마켓 필터와 keyword가 둘 다 오면 registered=false로 결합 조회한다")
	void getProducts_unregisteredMarketFilterAndKeyword_appliesBoth() {
		Page<Product> page = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
		when(productSearchUseCase.searchByMarketAndKeyword(
			eq(MarketType.COUPANG), eq(false), eq("shampoo"), any())).thenReturn(page);

		controller().getProducts("shampoo", "!COUPANG", PageRequest.of(0, 50));

		verify(productSearchUseCase).searchByMarketAndKeyword(MarketType.COUPANG, false, "shampoo",
			PageRequest.of(0, 50));
	}

	// ---- F-PROD-18: 크롤 URL 유효성 + 중복 제거 ----

	@Test
	@DisplayName("crawlSourceImages: 잘못된 형식 URL은 걸러내고 중복 URL은 순서 보존 제거한다")
	void crawlSourceImages_filtersInvalidAndDedups() {
		Product product = org.mockito.Mockito.mock(Product.class);
		when(productSearchUseCase.getProductDetail(7L)).thenReturn(product);
		when(product.getSourcingUrl()).thenReturn("http://src/7");
		when(productInfoCrawlerPort.crawlProductInfoAsDto("http://src/7"))
			.thenReturn(ScrapedProductDto.builder()
				.sourceImages(java.util.Arrays.asList(
					"http://img/a.jpg",
					"http://img/a.jpg", // 중복
					"not-a-url", // 형식 오류
					"  ", // 공백
					null, // null
					"https://img/b.jpg")) // 유효
				.build());

		ResponseEntity<List<String>> res = controller().crawlSourceImages(7L);

		assertThat(res.getBody()).containsExactly("http://img/a.jpg", "https://img/b.jpg");
	}

	// ---- F-PROD-22: 크롤 이미지 개수 상한(절단) ----

	@Test
	@DisplayName("crawlAndUpload: 크롤 이미지가 상한을 초과하면 상한 개수만큼 절단해 다운로드한다")
	void crawlAndUpload_exceedsCap_truncates() {
		Product product = org.mockito.Mockito.mock(Product.class);
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
