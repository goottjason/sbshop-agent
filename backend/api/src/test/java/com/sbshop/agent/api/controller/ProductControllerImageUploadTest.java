package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * D-049(반려 재수정): 이미지/HTML 업로드 엔드포인트가 마켓별 재게시 결과를 응답 본문에 실어 반환하는지 검증.
 *
 * <p>반려 사유: 컨트롤러가 {@code MarketRepublishResult}를 버리고 Void를 반환 → 일부 마켓 재게시가
 * 실패해도 사용자는 "성공"으로만 인지. 라이브 마켓 쓰기 실패가 조용히 삼켜짐.
 * 이 테스트는 부분 실패가 응답 본문의 {@code failed}에 실려 나오는지를 단언한다(현재는 null 본문 → Red).
 */
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
	private com.sbshop.agent.core.application.actionlog.ActionLogService actionLogService;

	private ProductController controller() {
		return new ProductController(productSearchUseCase, productManageUseCase,
			imageDownloadClient, productInfoCrawlerPort, marketRegistrationRepository, actionLogService);
	}

	/** 성공 1(CAFE24)·스킵 1(GMARKET)·실패 1(COUPANG) 혼재 결과. */
	private MarketRepublishResult mixedResult() {
		return new MarketRepublishResult(
			List.of(MarketType.CAFE24),
			List.of(MarketType.GMARKET),
			Map.of(MarketType.COUPANG, "429 Too Many Requests"));
	}

	@Test
	@DisplayName("uploadImagesByUrl: 마켓 부분 실패가 응답 본문의 failed 목록에 실려 반환된다")
	void uploadImagesByUrl_surfacesPartialMarketFailure() {
		when(imageDownloadClient.downloadAndConvert(any())).thenReturn(List.<ImageUploadFile>of());
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(mixedResult());

		ResponseEntity<ImageUploadResponse> res =
			controller().uploadImagesByUrl(1L, List.of("http://img/1.jpg"));

		ImageUploadResponse body = res.getBody();
		assertThat(body).as("응답 본문이 Void가 아니라 마켓 결과를 담아야 한다").isNotNull();
		assertThat(body.storageUpdated()).isTrue();
		// 라이브 마켓 부분 실패가 표면화됨
		assertThat(body.failed()).hasSize(1);
		assertThat(body.failed().get(0).market()).isEqualTo(MarketType.COUPANG.name());
		assertThat(body.failed().get(0).label()).isEqualTo(MarketType.COUPANG.getLabel());
		assertThat(body.failed().get(0).error()).contains("429");
		// 성공/스킵도 구분되어 전달
		assertThat(body.synced()).extracting(ImageUploadResponse.MarketOutcome::market)
			.containsExactly(MarketType.CAFE24.name());
		assertThat(body.skipped()).extracting(ImageUploadResponse.MarketOutcome::market)
			.containsExactly(MarketType.GMARKET.name());
	}

	@Test
	@DisplayName("uploadImages(multipart): 마켓 재게시 결과가 응답 본문에 실려 반환된다")
	void uploadImages_surfacesRepublishResult() {
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(mixedResult());

		// 빈 파일 리스트 → prepareImageFiles가 빈 목록 반환(Thumbnails 미실행), 배선 계약만 검증.
		ResponseEntity<ImageUploadResponse> res =
			controller().uploadImages(1L, List.of());

		ImageUploadResponse body = res.getBody();
		assertThat(body).as("응답 본문이 Void가 아니라 마켓 결과를 담아야 한다").isNotNull();
		assertThat(body.failed()).extracting(ImageUploadResponse.MarketFailure::market)
			.containsExactly(MarketType.COUPANG.name());
	}
}
