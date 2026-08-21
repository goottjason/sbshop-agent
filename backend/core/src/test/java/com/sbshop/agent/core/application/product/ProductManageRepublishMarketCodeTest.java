package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

@ExtendWith(MockitoExtension.class)
class ProductManageRepublishMarketCodeTest {
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

	@Test
	@DisplayName("SMART_STORE 등록의 originProductNo가 syncImagesAndHtml 첫 번째 인자로 전달된다")
	void smartStore_usesOriginProductNo_asMarketItemId() {
		MarketClient smartStoreClient = mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.SMART_STORE, "{\"originProductNo\":\"OP123\"}")));
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(smartStoreClient);

		useCase.updateImagesAndHtml(PRODUCT_ID, files());

		verify(smartStoreClient).syncImagesAndHtml(any(), eq("OP123"), any(),
			eq(List.of("https://r2.dev/a.jpg")), eq("<new/>"));
	}

	@Test
	@DisplayName("ELEVEN_STREET 등록의 prdNo가 syncImagesAndHtml 첫 번째 인자로 전달된다")
	void elevenStreet_usesPrdNo_asMarketItemId() {
		MarketClient elevenStreetClient = mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.ELEVEN_STREET, "{\"prdNo\":\"E11_999\"}")));
		when(marketClientRouter.hasClient(MarketType.ELEVEN_STREET)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.ELEVEN_STREET)).thenReturn(elevenStreetClient);

		useCase.updateImagesAndHtml(PRODUCT_ID, files());

		verify(elevenStreetClient).syncImagesAndHtml(any(), eq("E11_999"), any(),
			eq(List.of("https://r2.dev/a.jpg")), eq("<new/>"));
	}

	@Test
	@DisplayName("COUPANG 재게시는 vendorItemId가 아니라 sellerProductId를 syncImagesAndHtml 인자로 전달한다")
	void coupang_usesSellerProductId_notVendorItemId() {
		MarketClient coupangClient = mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG,
				"{\"sellerProductId\":\"11658784734\",\"vendorItemId\":\"73567246734\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);

		useCase.updateImagesAndHtml(PRODUCT_ID, files());

		verify(coupangClient).syncImagesAndHtml(any(), eq("11658784734"), any(),
			eq(List.of("https://r2.dev/a.jpg")), eq("<new/>"));
	}

	@Test
	@DisplayName("CAFE24 등록의 product_no가 syncImagesAndHtml 첫 번째 인자로 전달된다")
	void cafe24_usesProductNo_asMarketItemId() {
		MarketClient cafe24Client = mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.CAFE24, "{\"product_no\":\"CF456\"}")));
		when(marketClientRouter.hasClient(MarketType.CAFE24)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.CAFE24)).thenReturn(cafe24Client);

		useCase.updateImagesAndHtml(PRODUCT_ID, files());

		verify(cafe24Client).syncImagesAndHtml(any(), eq("CF456"), any(),
			eq(List.of("https://r2.dev/a.jpg")), eq("<new/>"));
	}

	@Test
	@DisplayName("마켓 상품코드 키 부재 시 해당 마켓은 failed로 수집되고 나머지 마켓은 계속 진행된다")
	void missingMarketCode_collectsAsFailed_otherMarketsStillSynced() {
		MarketClient smartStoreClient = mock(MarketClient.class);

		MarketClient elevenStreetClient = mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(
				reg(MarketType.SMART_STORE, "{}"),
				reg(MarketType.ELEVEN_STREET, "{\"prdNo\":\"E11_001\"}")));
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.hasClient(MarketType.ELEVEN_STREET)).thenReturn(true);

		lenient().when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(smartStoreClient);
		when(marketClientRouter.getClient(MarketType.ELEVEN_STREET)).thenReturn(elevenStreetClient);

		MarketRepublishResult result = useCase.updateImagesAndHtml(PRODUCT_ID, files());

		verify(smartStoreClient, never()).syncImagesAndHtml(any(), any(), any(), anyList(), any());

		verify(elevenStreetClient).syncImagesAndHtml(any(), eq("E11_001"), any(), anyList(), any());
		assertThat(result.failed()).containsKey(MarketType.SMART_STORE);
		assertThat(result.synced()).contains(MarketType.ELEVEN_STREET);
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
}
