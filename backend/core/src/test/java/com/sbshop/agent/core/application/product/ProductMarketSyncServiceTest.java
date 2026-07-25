package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
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
	@Mock private com.sbshop.agent.core.application.fee.MarketFeeService marketFeeService;

	private ProductMarketSyncService service;
	private static final Long PRODUCT_ID = 1L;

	@BeforeEach
	void setUp() {
		// 단일 가격 경로 검증이라 MarginCalculator/MarketFeeService는 실제로 호출되지 않는다.
		service = new ProductMarketSyncService(marketRegistrationRepository, marketClientRouter,
			new com.sbshop.agent.core.domain.product.service.MarginCalculator(), marketFeeService,
			org.mockito.Mockito.mock(com.sbshop.agent.core.domain.product.component.ProductReader.class));
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

		MarketRepublishResult result = service.syncPriceStock(PRODUCT_ID, 40700, StockStatus.IN_STOCK);

		verify(coupangClient).syncPriceAndStock(eq("CP123"), any(), eq(40700), eq(999), eq(false), any());
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

		MarketRepublishResult result = service.syncPriceStock(PRODUCT_ID, 1000, StockStatus.IN_STOCK);

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
		when(coupangClient.syncPriceAndStock(any(), any(), any(), anyInt(), anyBoolean(), any()))
			.thenThrow(new RuntimeException("쿠팡 API 오류"));

		MarketRepublishResult result = service.syncPriceStock(PRODUCT_ID, 1000, StockStatus.IN_STOCK);

		verify(storeClient).syncPriceAndStock(eq("OP99"), any(), eq(1000), eq(999), eq(false), any());
		assertThat(result.synced()).containsExactly(MarketType.SMART_STORE);
		assertThat(result.failed()).containsKey(MarketType.COUPANG);
	}

	private MarketRegistration cafe24Reg() {
		return reg(MarketType.CAFE24, "{\"product_no\":\"21159\"}");
	}

	@Test
	@DisplayName("변경없음+Cafe24 직전 동기화 성공(isSynced) → Cafe24 재전송 스킵")
	void changedFalse_cafe24Synced_skipsCafe24() {
		MarketRegistration cafe = cafe24Reg();
		cafe.markSynced();
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(cafe));

		MarketRepublishResult result = service.syncPriceStock(PRODUCT_ID, 38300, StockStatus.OUT_OF_STOCK, false);

		verify(marketClientRouter, never()).getClient(any());
		assertThat(result.skipped()).containsExactly(MarketType.CAFE24);
		assertThat(result.synced()).isEmpty();
	}

	@Test
	@DisplayName("변경없음이지만 Cafe24 직전 동기화 실패(isSynced=false) → 재시도(호출)")
	void changedFalse_cafe24NotSynced_callsCafe24() {
		MarketClient cafeClient = org.mockito.Mockito.mock(MarketClient.class);
		MarketRegistration cafe = cafe24Reg(); // isSynced=false 기본
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(cafe));
		when(marketClientRouter.hasClient(MarketType.CAFE24)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.CAFE24)).thenReturn(cafeClient);

		service.syncPriceStock(PRODUCT_ID, 38300, StockStatus.OUT_OF_STOCK, false);

		verify(cafeClient).syncPriceAndStock(eq("21159"), any(), eq(38300), eq(1), eq(true), any());
	}

	@Test
	@DisplayName("변경 있음이면 isSynced 무관 Cafe24 호출")
	void changedTrue_callsCafe24EvenIfSynced() {
		MarketClient cafeClient = org.mockito.Mockito.mock(MarketClient.class);
		MarketRegistration cafe = cafe24Reg();
		cafe.markSynced();
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(cafe));
		when(marketClientRouter.hasClient(MarketType.CAFE24)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.CAFE24)).thenReturn(cafeClient);

		service.syncPriceStock(PRODUCT_ID, 40000, StockStatus.IN_STOCK, true);

		verify(cafeClient).syncPriceAndStock(eq("21159"), any(), eq(40000), eq(999), eq(false), any());
	}

	@Test
	@DisplayName("변경없음이어도 스킵은 Cafe24 한정 — 쿠팡 등 타 마켓은 항상 호출")
	void changedFalse_nonCafe24AlwaysCalled() {
		MarketClient coupangClient = org.mockito.Mockito.mock(MarketClient.class);
		MarketRegistration cp = reg(MarketType.COUPANG, "{\"vendorItemId\":\"CP123\"}");
		cp.markSynced();
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(cp));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);

		service.syncPriceStock(PRODUCT_ID, 40700, StockStatus.IN_STOCK, false);

		verify(coupangClient).syncPriceAndStock(eq("CP123"), any(), eq(40700), eq(999), eq(false), any());
	}

	@Test
	@DisplayName("동기화 실패 시 등록행 isSynced=false로 리셋·저장(다음 배치 재시도 신호)")
	void syncFailure_marksRegSyncFailed() {
		MarketClient coupangClient = org.mockito.Mockito.mock(MarketClient.class);
		MarketRegistration cp = reg(MarketType.COUPANG, "{\"vendorItemId\":\"CP123\"}");
		cp.markSynced(); // 초기 isSynced=true
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(cp));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		when(coupangClient.syncPriceAndStock(any(), any(), any(), anyInt(), anyBoolean(), any()))
			.thenThrow(new RuntimeException("API 오류"));

		service.syncPriceStock(PRODUCT_ID, 1000, StockStatus.IN_STOCK);

		assertThat(cp.getIsSynced()).isFalse();
		verify(marketRegistrationRepository).save(cp);
	}
}
