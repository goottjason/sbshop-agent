package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cafe24SkipRetriesFailedWriteTest {

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketSalePriceResolver marketSalePriceResolver;
	@Mock
	private ProductReader productReader;
	@Mock
	private MarketClient client;
	@Mock
	private Product product;

	private static final Long PRODUCT_ID = 1665L;

	private ProductMarketSyncService service() {
		lenient().when(marketClientRouter.hasClient(any())).thenReturn(true);
		lenient().when(marketClientRouter.getClient(any())).thenReturn(client);
		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(client.syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any()))
			.thenReturn(Map.of("_sbshop_verified_fields", Map.of("fields", List.of("quantity"))));
		return new ProductMarketSyncService(marketRegistrationRepository, marketClientRouter,
			marketSalePriceResolver, productReader);
	}

	private MarketRegistration cafe24() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MarketType.CAFE24)
			.marketIdentifiers("{\"product_no\":\"12345\"}")
			.marketDetailedInfo("{}").build();
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(reg));
		return reg;
	}

	private MarketRepublishResult syncUnchanged() {
		return service().syncPriceStock(PRODUCT_ID, 10000, StockStatus.IN_STOCK, false);
	}

	@Test
	@DisplayName("D-286: 지난 쓰기가 실패했으면 값이 안 바뀌어도 다시 보낸다 — 마켓은 그 변경을 아직 못 받았다")
	void unchangedButLastWriteFailed_isRetried() {
		MarketRegistration reg = cafe24();
		reg.markSynced();
		reg.recordSyncError(SyncErrorType.TRANSIENT_ERROR, "504 Gateway Timeout");

		MarketRepublishResult result = syncUnchanged();

		assertThat(result.synced()).containsExactly(MarketType.CAFE24);
		assertThat(result.skipped()).isEmpty();
		verify(client).syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any());
	}

	@Test
	@DisplayName("카페24 재고 재조회 성공은 범위가 불명확한 과거 오류·전체 동기화 시각을 지우지 않는다")
	void successfulPartialRetryPreservesUnscopedError() {
		MarketRegistration reg = cafe24();
		reg.markSynced();
		reg.recordSyncError(SyncErrorType.TRANSIENT_ERROR, "504 Gateway Timeout");
		var previousSync = reg.getLastSyncedAt();
		var previousError = reg.getLastSyncErrorAt();

		syncUnchanged();

		assertThat(reg.getLastSyncError()).isEqualTo(SyncErrorType.TRANSIENT_ERROR);
		assertThat(reg.getLastSyncErrorAt()).isEqualTo(previousError);
		assertThat(reg.getLastSyncedAt()).isEqualTo(previousSync);
		assertThat(reg.getMarketDetailedInfo()).contains("_sbshop_verified_fields", "quantity");
	}

	@Test
	@DisplayName("카페24 과거 전체 성공 표지만으로 현재 필드 확인을 건너뛰지 않는다")
	void unchangedWithOnlyLegacyProof_isVerifiedAgain() {
		MarketRegistration reg = cafe24();
		reg.markSynced();

		MarketRepublishResult result = syncUnchanged();

		assertThat(result.skipped()).isEmpty();
		assertThat(result.synced()).containsExactly(MarketType.CAFE24);
		verify(client).syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any());
	}

	@Test
	@DisplayName("D-286: 마켓이 막아둔 상태는 값이 안 바뀌어도 다시 두드리지 않는다 — D-284 규칙이 우선한다")
	void blockedIsNotRetriedByThisRule() {
		MarketRegistration reg = cafe24();
		reg.markSynced();
		reg.recordSyncError(SyncErrorType.BLOCKED_BY_MARKET, "심사중");

		MarketRepublishResult result = syncUnchanged();

		assertThat(result.skipped()).containsExactly(MarketType.CAFE24);
		verify(client, never()).syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any());
	}
}
