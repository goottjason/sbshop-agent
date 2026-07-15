package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * F-PROD-12 / F-PROD-16(D-091·D-092): 개별 이미지 처리 실패의 표면화.
 *
 * <p>기존 결함 — 개별 이미지 리사이즈 실패({@code prepareImageFiles})와 by-url 개별 다운로드 실패
 * ({@code downloadAndConvert})가 로그만 남기고 조용히 드롭돼 응답이 200에 아무 표시가 없었다.
 * 이 테스트는 3장 중 1장이 실패하면 응답에 "성공 2장 / 실패 1장(사유)"이 표면화되고,
 * 전량 성공 시 실패 목록이 비는지를 단언한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerImagePartialFailureTest {

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
	private com.sbshop.agent.core.application.actionlog.ActionLogService actionLogService;

	private ProductController controller() {
		return new ProductController(productSearchUseCase, productManageUseCase,
			imageDownloadClient, productInfoCrawlerPort, marketRegistrationRepository, actionLogService);
	}

	private MarketRepublishResult noMarketResult() {
		return new MarketRepublishResult(List.of(), List.of(), Map.of());
	}

	private ImageUploadFile file(String name) {
		return new ImageUploadFile(name, "image/jpeg", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3);
	}

	// ---- F-PROD-16: by-url 개별 다운로드 실패 표면화 ----

	@Test
	@DisplayName("uploadImagesByUrl: 3장 중 1장 다운로드 실패가 응답 images.failed에 표면화된다(성공 2/실패 1)")
	void uploadImagesByUrl_surfacesPerUrlDownloadFailure() {
		List<String> urls = List.of("http://img/1.jpg", "http://img/2.jpg", "http://img/bad.jpg");
		when(imageDownloadClient.downloadAndConvertDetailed(urls)).thenReturn(
			ImageProcessResult.of(
				List.of(file("1.jpg"), file("2.jpg")),
				List.of(new ImageProcessResult.ImageFailure("http://img/bad.jpg", "HTTP 404"))));
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(noMarketResult());

		ResponseEntity<ImageUploadResponse> res = controller().uploadImagesByUrl(1L, urls);

		ImageUploadResponse body = res.getBody();
		assertThat(body).isNotNull();
		assertThat(body.imagesSucceeded()).isEqualTo(2);
		assertThat(body.imagesFailed()).hasSize(1);
		assertThat(body.imagesFailed().get(0).ref()).isEqualTo("http://img/bad.jpg");
		assertThat(body.imagesFailed().get(0).reason()).contains("404");
	}

	@Test
	@DisplayName("uploadImagesByUrl: 전량 성공 시 images.failed는 비어 있다")
	void uploadImagesByUrl_allSuccess_noFailures() {
		List<String> urls = List.of("http://img/1.jpg", "http://img/2.jpg");
		when(imageDownloadClient.downloadAndConvertDetailed(urls)).thenReturn(
			ImageProcessResult.of(List.of(file("1.jpg"), file("2.jpg")), List.of()));
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(noMarketResult());

		ImageUploadResponse body = controller().uploadImagesByUrl(1L, urls).getBody();

		assertThat(body).isNotNull();
		assertThat(body.imagesSucceeded()).isEqualTo(2);
		assertThat(body.imagesFailed()).isEmpty();
	}

	// ---- F-PROD-12: multipart 리사이즈 실패 표면화 ----

	@Test
	@DisplayName("uploadImages: 3장 중 1장 리사이즈 실패가 응답 images.failed에 표면화된다(성공 2/실패 1)")
	void uploadImages_surfacesPerImageResizeFailure() {
		// 1·2장은 정상 JPEG, 3장은 이미지가 아닌 바이트 → Thumbnails 리사이즈 실패
		MultipartFile ok1 = new MockMultipartFile("images", "ok1.jpg", "image/jpeg", jpegBytes());
		MultipartFile ok2 = new MockMultipartFile("images", "ok2.jpg", "image/jpeg", jpegBytes());
		MultipartFile bad = new MockMultipartFile("images", "bad.jpg", "image/jpeg", new byte[] {0, 1, 2, 3});
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(noMarketResult());

		ImageUploadResponse body = controller().uploadImages(1L, List.of(ok1, ok2, bad)).getBody();

		assertThat(body).isNotNull();
		assertThat(body.imagesSucceeded()).isEqualTo(2);
		assertThat(body.imagesFailed()).hasSize(1);
		assertThat(body.imagesFailed().get(0).ref()).isEqualTo("bad.jpg");
		assertThat(body.imagesFailed().get(0).reason()).isNotBlank();
	}

	/** 최소 유효 JPEG 1x1 (thumbnailator가 디코드 가능). */
	private static byte[] jpegBytes() {
		try {
			java.awt.image.BufferedImage img =
				new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(img, "jpg", os);
			return os.toByteArray();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
