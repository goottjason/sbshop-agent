package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.api.dto.product.PriceStockUpdateRequest;
import com.sbshop.agent.api.dto.product.ProductUpdateRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ProductControllerInputValidationTest {
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

	private ProductUpdateRequest updateRequestWith(BigDecimal salePrice, Integer stock, BigDecimal costPrice) {
		return new ProductUpdateRequest(
			"brand", "name", "baseName", "originalName", null,
			costPrice, null, null, null, salePrice,
			stock, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null, null, null, null);
	}

	@Test
	@DisplayName("updatePriceStock: 음수 price는 IllegalArgumentException(400)으로 거부하고 usecase를 호출하지 않는다")
	void updatePriceStock_negativePrice_rejected() {
		PriceStockUpdateRequest req = new PriceStockUpdateRequest(new BigDecimal("-1"), Boolean.FALSE);

		assertThatThrownBy(() -> controller().updatePriceStock(1L, req))
			.isInstanceOf(IllegalArgumentException.class);

		verify(productManageUseCase, never()).updatePriceStock(anyLong(), any(), any());
	}

	@Test
	@DisplayName("updatePriceStock: null price(가격 미변경)와 0 이상 price는 정상 처리한다")
	void updatePriceStock_nullOrNonNegativePrice_ok() {
		when(productManageUseCase.updatePriceStock(anyLong(), any(), any())).thenReturn(noMarketResult());

		controller().updatePriceStock(1L, new PriceStockUpdateRequest(null, null));
		controller().updatePriceStock(1L, new PriceStockUpdateRequest(BigDecimal.ZERO, Boolean.TRUE));

		verify(productManageUseCase).updatePriceStock(1L, null, null);
		verify(productManageUseCase).updatePriceStock(1L, BigDecimal.ZERO, Boolean.TRUE);
	}

	@Test
	@DisplayName("uploadImagesByUrl: null 이미지 목록은 IllegalArgumentException(400)으로 거부한다")
	void uploadImagesByUrl_nullList_rejected() {
		assertThatThrownBy(() -> controller().uploadImagesByUrl(1L, null))
			.isInstanceOf(IllegalArgumentException.class);

		verify(imageDownloadClient, never()).downloadAndConvertDetailed(any());
	}

	@Test
	@DisplayName("uploadImagesByUrl: 빈 이미지 목록은 IllegalArgumentException(400)으로 거부한다")
	void uploadImagesByUrl_emptyList_rejected() {
		assertThatThrownBy(() -> controller().uploadImagesByUrl(1L, List.of()))
			.isInstanceOf(IllegalArgumentException.class);

		verify(imageDownloadClient, never()).downloadAndConvertDetailed(any());
	}

	@Test
	@DisplayName("uploadImagesByUrl: 유효한 URL 목록은 기존 경로(다운로드→집계→응답)를 그대로 탄다")
	void uploadImagesByUrl_validList_processed() {
		List<String> urls = List.of("http://img/1.jpg");
		when(imageDownloadClient.downloadAndConvertDetailed(urls)).thenReturn(
			ImageProcessResult.of(List.of(), List.of()));
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(noMarketResult());

		ResponseEntity<ImageUploadResponse> res = controller().uploadImagesByUrl(1L, urls);

		assertThat(res.getBody()).isNotNull();
		verify(imageDownloadClient).downloadAndConvertDetailed(urls);
	}

	@Test
	@DisplayName("updateProduct: 음수 salePrice는 IllegalArgumentException(400)으로 거부하고 usecase를 호출하지 않는다")
	void updateProduct_negativeSalePrice_rejected() {
		ProductUpdateRequest req = updateRequestWith(new BigDecimal("-100"), 10, null);

		assertThatThrownBy(() -> controller().updateProduct(1L, req))
			.isInstanceOf(IllegalArgumentException.class);

		verify(productManageUseCase, never()).updateProduct(anyLong(), any());
	}

	@Test
	@DisplayName("updateProduct: 음수 stock은 IllegalArgumentException(400)으로 거부한다")
	void updateProduct_negativeStock_rejected() {
		ProductUpdateRequest req = updateRequestWith(new BigDecimal("100"), -5, null);

		assertThatThrownBy(() -> controller().updateProduct(1L, req))
			.isInstanceOf(IllegalArgumentException.class);

		verify(productManageUseCase, never()).updateProduct(anyLong(), any());
	}

	@Test
	@DisplayName("updateProduct: 음수 costPrice는 IllegalArgumentException(400)으로 거부한다")
	void updateProduct_negativeCostPrice_rejected() {
		ProductUpdateRequest req = updateRequestWith(new BigDecimal("100"), 10, new BigDecimal("-1"));

		assertThatThrownBy(() -> controller().updateProduct(1L, req))
			.isInstanceOf(IllegalArgumentException.class);

		verify(productManageUseCase, never()).updateProduct(anyLong(), any());
	}

	@Test
	@DisplayName("updateProduct: 모든 금액·수량이 0 이상(또는 null)이면 정상 처리한다")
	void updateProduct_nonNegative_ok() {
		ProductUpdateRequest req = updateRequestWith(new BigDecimal("100"), 0, BigDecimal.ZERO);

		controller().updateProduct(1L, req);

		verify(productManageUseCase).updateProduct(anyLong(), any());
	}
}
