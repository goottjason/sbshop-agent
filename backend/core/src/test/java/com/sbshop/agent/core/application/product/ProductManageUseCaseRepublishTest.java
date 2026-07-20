package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.component.HtmlImageReplacer;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-049(결정②): updateImagesAndHtml 완료 후 각 연동 마켓으로의 이미지/HTML 자동 재게시 배선 검증.
 * - 클라이언트가 있는 마켓은 syncImagesAndHtml 호출, GMARKET/AUCTION(D-044, 구현체 없음)은 스킵.
 * - 한 마켓 예외가 나머지 마켓·자사 DB 갱신을 롤백하지 않음(부분 실패 수집).
 */
@ExtendWith(MockitoExtension.class)
class ProductManageUseCaseRepublishTest {

	@Mock
	private ProductReader productReader;
	@Mock
	private ProductWriter productWriter;
	@Mock
	private ImageStorageClient imageStorageClient;
	@Mock
	private HtmlImageReplacer htmlImageReplacer;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private ProductMarketSyncService productMarketSyncService;

	private ProductManageUseCase useCase;

	private static final Long PRODUCT_ID = 1L;

	@Mock
	private Product product;

	@BeforeEach
	void setUp() {
		useCase = new ProductManageUseCase(productReader, productWriter, imageStorageClient,
			htmlImageReplacer, marketRegistrationRepository, marketClientRouter, productMarketSyncService, null, null);

		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(product.getSbCode()).thenReturn("SB1");
		lenient().when(product.getDetailHtml()).thenReturn("<old/>");
		lenient().when(imageStorageClient.uploadImages(any()))
			.thenReturn(Map.of("a.jpg", "https://r2.dev/a.jpg"));
		lenient().when(htmlImageReplacer.replaceImagesBySku(any(), any(), anyList()))
			.thenReturn("<new/>");
	}

	private MarketRegistration reg(MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(PRODUCT_ID)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.marketDetailedInfo("{}")
			.build();
	}

	private List<ImageUploadFile> files() {
		return List.of(new ImageUploadFile("a.jpg", "image/jpeg", null, 10));
	}

	@Test
	@DisplayName("이미지 갱신 후 클라이언트가 있는 마켓별로 syncImagesAndHtml을 호출한다")
	void updateImagesAndHtml_republishesToRegisteredMarkets() {
		MarketClient coupangClient = org.mockito.Mockito.mock(MarketClient.class);
		// 쿠팡 재게시는 seller-products 엔드포인트 → sellerProductId 사용(vendorItemId 아님)
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG, "{\"sellerProductId\":\"CP123\",\"vendorItemId\":\"VI999\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);

		useCase.updateImagesAndHtml(PRODUCT_ID, files());

		verify(coupangClient).syncImagesAndHtml(any(), eq("CP123"), any(),
			eq(List.of("https://r2.dev/a.jpg")), eq("<new/>"));
		verify(productWriter).save(product);
	}

	@Test
	@DisplayName("GMARKET/AUCTION 등 클라이언트가 없는 마켓은 재게시에서 스킵하고 크래시하지 않는다")
	void updateImagesAndHtml_skipsMarketsWithoutClient() {
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.GMARKET, "{}"), reg(MarketType.AUCTION, "{}")));
		when(marketClientRouter.hasClient(MarketType.GMARKET)).thenReturn(false);
		when(marketClientRouter.hasClient(MarketType.AUCTION)).thenReturn(false);

		useCase.updateImagesAndHtml(PRODUCT_ID, files());

		verify(marketClientRouter, never()).getClient(MarketType.GMARKET);
		verify(marketClientRouter, never()).getClient(MarketType.AUCTION);
		verify(productWriter).save(product);
	}

	@Test
	@DisplayName("한 마켓 재게시 실패가 다른 마켓·자사 DB 갱신을 막지 않는다(부분 실패 수집)")
	void updateImagesAndHtml_partialFailureDoesNotBlockOthers() {
		MarketClient coupangClient = org.mockito.Mockito.mock(MarketClient.class);
		MarketClient cafe24Client = org.mockito.Mockito.mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG, "{\"sellerProductId\":\"CP123\"}"),
				reg(MarketType.CAFE24, "{\"product_no\":\"C24\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.hasClient(MarketType.CAFE24)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		when(marketClientRouter.getClient(MarketType.CAFE24)).thenReturn(cafe24Client);
		when(coupangClient.syncImagesAndHtml(any(), any(), any(), anyList(), any()))
			.thenThrow(new RuntimeException("쿠팡 API 오류"));

		useCase.updateImagesAndHtml(PRODUCT_ID, files());

		verify(cafe24Client).syncImagesAndHtml(any(), any(), any(), anyList(), any());
		verify(productWriter).save(product);
	}
}
