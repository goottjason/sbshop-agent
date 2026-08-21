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
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

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
	private ActionLogService actionLogService;

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

	private static byte[] jpegBytes() {
		try {
			BufferedImage img = new BufferedImage(2, 2,
				BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			ImageIO.write(img, "jpg", os);
			return os.toByteArray();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

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

	@Test
	@DisplayName("uploadImages: 3장 중 1장 리사이즈 실패가 응답 images.failed에 표면화된다(성공 2/실패 1)")
	void uploadImages_surfacesPerImageResizeFailure() {
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
}
