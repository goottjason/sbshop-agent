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

/**
 * D-052: republishToMarkets가 extractVendorItemId(쿠팡 전용) 대신
 * extractMarketCode(마켓별 올바른 코드 키)를 사용하는지 검증.
 * - SMART_STORE: originProductNo 키를 읽어 클라이언트에 전달
 * - ELEVEN_STREET: elevenstId/prdNo 키를 읽어 전달
 * - CAFE24: product_no/product_code 키를 읽어 전달
 * - 코드 키 부재(null/empty) 시 해당 마켓을 failed 로 수집하고 나머지 마켓은 계속 진행
 */
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
			htmlImageReplacer, marketRegistrationRepository, marketClientRouter, productMarketSyncService);

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
	@DisplayName("SMART_STORE 등록의 originProductNo가 syncImagesAndHtml 첫 번째 인자로 전달된다")
	void smartStore_usesOriginProductNo_asMarketItemId() {
		MarketClient smartStoreClient = mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.SMART_STORE, "{\"originProductNo\":\"OP123\"}")));
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(smartStoreClient);

		useCase.updateImagesAndHtml(PRODUCT_ID, files());

		// extractMarketCode()는 SMART_STORE에서 originProductNo를 읽어야 함
		verify(smartStoreClient).syncImagesAndHtml(eq("OP123"), any(),
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

		verify(elevenStreetClient).syncImagesAndHtml(eq("E11_999"), any(),
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

		verify(cafe24Client).syncImagesAndHtml(eq("CF456"), any(),
			eq(List.of("https://r2.dev/a.jpg")), eq("<new/>"));
	}

	@Test
	@DisplayName("마켓 상품코드 키 부재 시 해당 마켓은 failed로 수집되고 나머지 마켓은 계속 진행된다")
	void missingMarketCode_collectsAsFailed_otherMarketsStillSynced() {
		MarketClient smartStoreClient = mock(MarketClient.class);
		// SMART_STORE: 코드 키 없음 ({}에서 originProductNo/channelProductNo 없음)
		// ELEVEN_STREET: 코드 있음
		MarketClient elevenStreetClient = mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(
				reg(MarketType.SMART_STORE, "{}"),              // 코드 없음 → failed
				reg(MarketType.ELEVEN_STREET, "{\"prdNo\":\"E11_001\"}")  // 코드 있음 → synced
			));
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.hasClient(MarketType.ELEVEN_STREET)).thenReturn(true);
		// SMART_STORE: extractMarketCode() → null → throws before getClient — 스텁 불필요
		lenient().when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(smartStoreClient);
		when(marketClientRouter.getClient(MarketType.ELEVEN_STREET)).thenReturn(elevenStreetClient);

		MarketRepublishResult result = useCase.updateImagesAndHtml(PRODUCT_ID, files());

		// 코드 없는 SMART_STORE는 클라이언트 호출 없이 failed 수집
		verify(smartStoreClient, never()).syncImagesAndHtml(any(), any(), anyList(), any());
		// 코드 있는 ELEVEN_STREET는 정상 호출
		verify(elevenStreetClient).syncImagesAndHtml(eq("E11_001"), any(), anyList(), any());
		assertThat(result.failed()).containsKey(MarketType.SMART_STORE);
		assertThat(result.synced()).contains(MarketType.ELEVEN_STREET);
	}
}
