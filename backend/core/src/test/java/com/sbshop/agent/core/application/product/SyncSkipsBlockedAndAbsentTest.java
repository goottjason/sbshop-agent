package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.time.LocalDateTime;
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
import org.springframework.test.util.ReflectionTestUtils;
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
class SyncSkipsBlockedAndAbsentTest {

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

	private static final Long PRODUCT_ID = 77L;
	private static final MarketType MARKET = MarketType.COUPANG;

	private ProductMarketSyncService service() {
		lenient().when(marketClientRouter.hasClient(any())).thenReturn(true);
		lenient().when(marketClientRouter.getClient(any())).thenReturn(client);
		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(client.syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any()))
			.thenReturn(Map.of());
		return new ProductMarketSyncService(marketRegistrationRepository, marketClientRouter,
			marketSalePriceResolver, productReader);
	}

	private MarketRegistration registration() {
		return MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"11002709448\"}")
			.marketDetailedInfo("{}").build();
	}

	private void given(MarketRegistration reg) {
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(reg));
	}

	private MarketRepublishResult sync() {
		return service().syncPriceStock(PRODUCT_ID, 10000, StockStatus.IN_STOCK, true);
	}

	@Test
	@DisplayName("D-284: 마켓이 막아둔 등록은 전송하지 않고 건너뛴다 — 재시도로는 풀리지 않는다")
	void blockedRegistrationIsSkipped() {
		MarketRegistration reg = registration();
		reg.recordSyncError(SyncErrorType.BLOCKED_BY_MARKET, "판매중지된 상품입니다");
		given(reg);

		MarketRepublishResult result = sync();

		assertThat(result.skipped()).containsExactly(MARKET);
		assertThat(result.failed()).isEmpty();
		verify(client, never()).syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any());
	}

	@Test
	@DisplayName("D-284: 막힌 지 7일이 지나면 한 번 다시 두드린다 — 심사는 언젠가 끝난다")
	void blockedRegistrationIsRetriedAfterWindow() {
		MarketRegistration reg = registration();
		reg.recordSyncError(SyncErrorType.BLOCKED_BY_MARKET, "심사중");
		ReflectionTestUtils.setField(reg, "lastSyncErrorAt", LocalDateTime.now().minusDays(8));
		given(reg);

		MarketRepublishResult result = sync();

		assertThat(result.synced()).containsExactly(MARKET);
		verify(client).syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any());
	}

	@Test
	@DisplayName("D-284: 마켓에서 삭제된 등록은 전송하지 않는다 — 없는 상품에 가격을 보낼 수 없다")
	void deletedRegistrationIsSkipped() {
		MarketRegistration reg = registration();
		reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		given(reg);

		MarketRepublishResult result = sync();

		assertThat(result.skipped()).containsExactly(MARKET);
		verify(client, never()).syncPriceAndStock(anyString(), any(), any(), anyInt(), anyBoolean(), any());
	}

	@Test
	@DisplayName("D-284: 아직 한 번도 올린 적 없는 등록은 건너뛰지 않는다 — 첫 전송은 시도해야 한다")
	void neverSyncedRegistrationIsNotSkipped() {
		MarketRegistration reg = registration();
		reg.markAbsentFromMarket(UnsyncReason.NEVER_SYNCED);
		given(reg);

		MarketRepublishResult result = sync();

		assertThat(result.synced()).containsExactly(MARKET);
	}

	@Test
	@DisplayName("D-284: 일시 오류는 건너뛰지 않는다 — 다시 시도하면 풀릴 수 있다")
	void transientErrorIsNotSkipped() {
		MarketRegistration reg = registration();
		reg.recordSyncError(SyncErrorType.TRANSIENT_ERROR, "504 Gateway Timeout");
		given(reg);

		MarketRepublishResult result = sync();

		assertThat(result.synced()).containsExactly(MARKET);
	}

	@Test
	@DisplayName("D-284: 정상 등록은 그대로 전송한다")
	void healthyRegistrationIsSynced() {
		MarketRegistration reg = registration();
		reg.markSynced();
		given(reg);

		MarketRepublishResult result = sync();

		assertThat(result.synced()).containsExactly(MARKET);
	}
}
