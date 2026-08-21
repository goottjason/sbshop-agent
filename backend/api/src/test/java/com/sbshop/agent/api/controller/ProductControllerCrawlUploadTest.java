package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * SP-C Task 5: 크롤→업로드 원클릭 엔드포인트 {@code POST /{id}/images/crawl-and-upload} 검증.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerCrawlUploadTest {

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

	private ImageUploadFile dummyFile(String name) {
		return new ImageUploadFile(name, "image/jpeg",
			new ByteArrayInputStream(new byte[] {1, 2, 3}), 3);
	}

	@Test
	@DisplayName("crawlAndUpload: 정상 경로 — 크롤 URL 목록 downloadAndConvertDetailed 후 updateImagesAndHtml 호출")
	void crawlAndUpload_happyPath_callsDownloadAndUpdate() {
		Product product = org.mockito.Mockito.mock(Product.class);
		when(productSearchUseCase.getProductDetail(7L)).thenReturn(product);
		when(product.getSourcingUrl()).thenReturn("http://iherb/p/7");

		when(productInfoCrawlerPort.crawlProductInfoAsDto("http://iherb/p/7"))
			.thenReturn(ScrapedProductDto.builder()
				.sourceImages(List.of("http://img/u0.jpg", "http://img/u1.jpg"))
				.build());

		List<ImageUploadFile> files = List.of(dummyFile("u0.jpg"), dummyFile("u1.jpg"));
		when(imageDownloadClient.downloadAndConvertDetailed(List.of("http://img/u0.jpg", "http://img/u1.jpg")))
			.thenReturn(ImageProcessResult.of(files, List.of()));

		MarketRepublishResult result = new MarketRepublishResult(List.of(), List.of(), Map.of());
		when(productManageUseCase.updateImagesAndHtml(7L, files)).thenReturn(result);

		ResponseEntity<ImageUploadResponse> response = controller().crawlAndUpload(7L);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		org.mockito.InOrder inOrderVerify = inOrder(imageDownloadClient, productManageUseCase);
		inOrderVerify.verify(imageDownloadClient)
			.downloadAndConvertDetailed(eq(List.of("http://img/u0.jpg", "http://img/u1.jpg")));
		inOrderVerify.verify(productManageUseCase).updateImagesAndHtml(eq(7L), eq(files));
	}

	@Test
	@DisplayName("crawlAndUpload: 소싱 URL 없으면 updateImagesAndHtml 미호출, 200 반환")
	void crawlAndUpload_emptySourceUrl_skipsUpdate() {
		Product product = org.mockito.Mockito.mock(Product.class);
		when(productSearchUseCase.getProductDetail(7L)).thenReturn(product);
		when(product.getSourcingUrl()).thenReturn(null);

		ResponseEntity<ImageUploadResponse> response = controller().crawlAndUpload(7L);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		verify(productManageUseCase, never()).updateImagesAndHtml(anyLong(), any());
	}

	@Test
	@DisplayName("crawlAndUpload: 크롤 결과 이미지 0개면 updateImagesAndHtml 미호출, 200 반환")
	void crawlAndUpload_emptyCrawlImages_skipsUpdate() {
		Product product = org.mockito.Mockito.mock(Product.class);
		when(productSearchUseCase.getProductDetail(7L)).thenReturn(product);
		when(product.getSourcingUrl()).thenReturn("http://iherb/p/7");
		when(productInfoCrawlerPort.crawlProductInfoAsDto("http://iherb/p/7"))
			.thenReturn(ScrapedProductDto.builder().sourceImages(List.of()).build());

		ResponseEntity<ImageUploadResponse> response = controller().crawlAndUpload(7L);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		verify(productManageUseCase, never()).updateImagesAndHtml(anyLong(), any());
	}
}
