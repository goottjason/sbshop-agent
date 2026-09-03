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
			.thenReturn(Map.of());
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
	@DisplayName("D-286: 재전송에 성공하면 옛 오류가 지워져 다시 조용한 스킵으로 돌아간다")
	void successfulRetryClearsTheError() {
		MarketRegistration reg = cafe24();
		reg.markSynced();
		reg.recordSyncError(SyncErrorType.TRANSIENT_ERROR, "504 Gateway Timeout");

		syncUnchanged();

		assertThat(reg.getLastSyncError()).isNull();
		assertThat(reg.getLastSyncErrorAt()).isNull();
	}

	@Test
	@DisplayName("D-286: 지난 쓰기가 성공했고 값도 안 바뀌었으면 그대로 건너뛴다")
	void unchangedAndHealthy_isStillSkipped() {
		MarketRegistration reg = cafe24();
		reg.markSynced();

		MarketRepublishResult result = syncUnchanged();

		assertThat(result.skipped()).containsExactly(MarketType.CAFE24);
		verify(client, never()).syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any());
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
