package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-060: 상품의 연동 마켓별 가격/재고 반영(단건·배치 공용). 순회 호출·스킵·부분 실패 수집 검증.
 */
@ExtendWith(MockitoExtension.class)
class ProductMarketSyncServiceTest {

	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private MarketClientRouter marketClientRouter;

	private ProductMarketSyncService service;
	private static final Long PRODUCT_ID = 1L;

	@BeforeEach
	void setUp() {
		service = new ProductMarketSyncService(marketRegistrationRepository, marketClientRouter);
	}

	private MarketRegistration reg(MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(PRODUCT_ID)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.marketDetailedInfo("{}")
			.build();
	}

	@Test
	@DisplayName("클라이언트가 있는 마켓별로 syncPriceAndStock을 호출한다")
	void syncsToRegisteredMarkets() {
		MarketClient coupangClient = org.mockito.Mockito.mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG, "{\"vendorItemId\":\"CP123\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);

		MarketRepublishResult result = service.syncPriceStock(PRODUCT_ID, 40700, 500);

		verify(coupangClient).syncPriceAndStock(eq("CP123"), any(), eq(40700), eq(500));
		assertThat(result.synced()).containsExactly(MarketType.COUPANG);
		assertThat(result.failed()).isEmpty();
	}

	@Test
	@DisplayName("클라이언트가 없는 마켓(GMARKET/AUCTION)은 스킵하고 크래시하지 않는다")
	void skipsMarketsWithoutClient() {
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.GMARKET, "{}"), reg(MarketType.AUCTION, "{}")));
		when(marketClientRouter.hasClient(MarketType.GMARKET)).thenReturn(false);
		when(marketClientRouter.hasClient(MarketType.AUCTION)).thenReturn(false);

		MarketRepublishResult result = service.syncPriceStock(PRODUCT_ID, 1000, 3);

		verify(marketClientRouter, never()).getClient(any());
		assertThat(result.skipped()).containsExactlyInAnyOrder(MarketType.GMARKET, MarketType.AUCTION);
		assertThat(result.synced()).isEmpty();
	}

	@Test
	@DisplayName("한 마켓 동기화 실패가 다른 마켓을 막지 않는다(부분 실패 수집)")
	void partialFailureCollected() {
		MarketClient coupangClient = org.mockito.Mockito.mock(MarketClient.class);
		MarketClient storeClient = org.mockito.Mockito.mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG, "{\"vendorItemId\":\"CP123\"}"),
				reg(MarketType.SMART_STORE, "{\"originProductNo\":\"OP99\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(storeClient);
		when(coupangClient.syncPriceAndStock(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("쿠팡 API 오류"));

		MarketRepublishResult result = service.syncPriceStock(PRODUCT_ID, 1000, 3);

		verify(storeClient).syncPriceAndStock(eq("OP99"), any(), eq(1000), eq(3));
		assertThat(result.synced()).containsExactly(MarketType.SMART_STORE);
		assertThat(result.failed()).containsKey(MarketType.COUPANG);
	}
}
