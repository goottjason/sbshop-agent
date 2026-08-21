package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.PriceStockUpdateRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductControllerActionLogDetailTest {
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

	private MarketRegistration reg(Long productId, MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(productId)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.build();
	}

	private MarketRepublishResult mixedResult() {
		return new MarketRepublishResult(
			List.of(MarketType.COUPANG),
			List.of(MarketType.GMARKET),
			Map.of(MarketType.SMART_STORE, "401 Unauthorized"));
	}

	private String capturedSuccessMessage(String actionType) {
		ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
		verify(actionLogService).record(eq(actionType), isNull(), eq(ActionStatus.SUCCESS), msg.capture());
		return msg.getValue();
	}

	private ScrapedProductDto scrapedWith(List<String> sourceImages) {
		return ScrapedProductDto.builder().sourceImages(sourceImages).build();
	}

	@Test
	@DisplayName("updatePriceStock: 활동로그 메시지에 마켓별 상품번호와 성공/스킵/실패 상세가 담긴다")
	void updatePriceStock_recordsMarketDetail() {
		when(productManageUseCase.updatePriceStock(anyLong(), any(), anyBoolean())).thenReturn(mixedResult());
		when(marketRegistrationRepository.findByProductId(1L)).thenReturn(List.of(
			reg(1L, MarketType.COUPANG, "{\"vendorItemId\":\"7283748383\"}"),
			reg(1L, MarketType.SMART_STORE, "{\"originProductNo\":\"2939395\"}")));

		controller().updatePriceStock(1L,
			new PriceStockUpdateRequest(BigDecimal.valueOf(1000), false));

		String message = capturedSuccessMessage(ActionLogConstants.PRODUCT_PRICE_STOCK_UPDATE);
		assertThat(message).contains("쿠팡 7283748383 성공");
		assertThat(message).contains("N스토어 2939395 실패");
		assertThat(message).contains("401 Unauthorized");
		assertThat(message).contains("G마켓");
		assertThat(message).contains("DB");
	}

	@Test
	@DisplayName("updatePriceStock: 마켓 실패사유는 50자로 절단되어 기록된다")
	void updatePriceStock_truncatesFailureReasonTo50Chars() {
		String longReason = "X".repeat(120);
		when(productManageUseCase.updatePriceStock(anyLong(), any(), anyBoolean())).thenReturn(
			new MarketRepublishResult(List.of(), List.of(),
				Map.of(MarketType.COUPANG, longReason)));
		when(marketRegistrationRepository.findByProductId(1L)).thenReturn(List.of(
			reg(1L, MarketType.COUPANG, "{\"vendorItemId\":\"CP1\"}")));

		controller().updatePriceStock(1L,
			new PriceStockUpdateRequest(BigDecimal.valueOf(1000), false));

		String message = capturedSuccessMessage(ActionLogConstants.PRODUCT_PRICE_STOCK_UPDATE);
		assertThat(message).doesNotContain(longReason);
		assertThat(message).contains("X".repeat(50));
	}

	@Test
	@DisplayName("uploadImagesByUrl: 활동로그 메시지에도 마켓별 상세가 담긴다")
	void uploadImagesByUrl_recordsMarketDetail() {
		when(imageDownloadClient.downloadAndConvertDetailed(any())).thenReturn(
			ImageProcessResult.of(List.of(), List.of()));
		when(productManageUseCase.updateImagesAndHtml(anyLong(), any())).thenReturn(mixedResult());
		when(marketRegistrationRepository.findByProductId(1L)).thenReturn(List.of(
			reg(1L, MarketType.COUPANG, "{\"vendorItemId\":\"7283748383\"}")));

		controller().uploadImagesByUrl(1L, List.of("http://img/1.jpg"));

		String message = capturedSuccessMessage(ActionLogConstants.PRODUCT_IMAGE_UPDATE);
		assertThat(message).contains("쿠팡 7283748383 성공");
		assertThat(message).contains("N스토어 실패");
	}

	@Test
	@DisplayName("crawlSourceImages: 이미지 수집 성공 시 SOURCE_IMAGE_CRAWL SUCCESS 로그를 기록한다")
	void crawlSourceImages_recordsSuccess() {
		Product product = Mockito.mock(Product.class);
		when(productSearchUseCase.getProductDetail(1L)).thenReturn(product);
		when(product.getSourcingUrl()).thenReturn("http://iherb/p/1");
		when(productInfoCrawlerPort.crawlProductInfoAsDto("http://iherb/p/1"))
			.thenReturn(scrapedWith(List.of("http://img/a.jpg", "http://img/b.jpg")));

		controller().crawlSourceImages(1L);

		ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
		verify(actionLogService).record(eq(ActionLogConstants.SOURCE_IMAGE_CRAWL), isNull(),
			eq(ActionStatus.SUCCESS), msg.capture());
		assertThat(msg.getValue()).contains("2");
	}

	@Test
	@DisplayName("crawlSourceImages: 소싱 URL 없으면 결과 없음을 로그로 남긴다")
	void crawlSourceImages_recordsEmptyWhenNoSourcingUrl() {
		Product product = Mockito.mock(Product.class);
		when(productSearchUseCase.getProductDetail(1L)).thenReturn(product);
		when(product.getSourcingUrl()).thenReturn(null);

		controller().crawlSourceImages(1L);

		verify(actionLogService).record(eq(ActionLogConstants.SOURCE_IMAGE_CRAWL), isNull(),
			any(), any());
	}
}
