package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ProductControllerImageUploadTest {

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

	private MarketRepublishResult mixedResult() {
		return new MarketRepublishResult(
			List.of(MarketType.CAFE24),
			List.of(MarketType.GMARKET),
			Map.of(MarketType.COUPANG, "429 Too Many Requests"));
	}

	@Test
	@DisplayName("uploadImagesByUrl: 마켓 부분 실패가 응답 본문의 failed 목록에 실려 반환된다")
	void uploadImagesByUrl_surfacesPartialMarketFailure() {
		when(imageDownloadClient.downloadAndConvertDetailed(any())).thenReturn(
			ImageProcessResult.of(
				List.<ImageUploadFile>of(), List.of()));
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(mixedResult());

		ResponseEntity<ImageUploadResponse> res =
			controller().uploadImagesByUrl(1L, List.of("http://img/1.jpg"));

		ImageUploadResponse body = res.getBody();
		assertThat(body).as("응답 본문이 Void가 아니라 마켓 결과를 담아야 한다").isNotNull();
		assertThat(body.storageUpdated()).isTrue();
		assertThat(body.failed()).hasSize(1);
		assertThat(body.failed().get(0).market()).isEqualTo(MarketType.COUPANG.name());
		assertThat(body.failed().get(0).label()).isEqualTo(MarketType.COUPANG.getLabel());
		assertThat(body.failed().get(0).error()).contains("429");
		assertThat(body.synced()).extracting(ImageUploadResponse.MarketOutcome::market)
			.containsExactly(MarketType.CAFE24.name());
		assertThat(body.skipped()).extracting(ImageUploadResponse.MarketOutcome::market)
			.containsExactly(MarketType.GMARKET.name());
	}

	@Test
	@DisplayName("uploadImages(multipart): 마켓 재게시 결과가 응답 본문에 실려 반환된다")
	void uploadImages_surfacesRepublishResult() {
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(mixedResult());

		ResponseEntity<ImageUploadResponse> res =
			controller().uploadImages(1L, List.of());

		ImageUploadResponse body = res.getBody();
		assertThat(body).as("응답 본문이 Void가 아니라 마켓 결과를 담아야 한다").isNotNull();
		assertThat(body.failed()).extracting(ImageUploadResponse.MarketFailure::market)
			.containsExactly(MarketType.COUPANG.name());
	}
}
