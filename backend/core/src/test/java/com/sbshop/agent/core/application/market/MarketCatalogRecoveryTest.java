package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.market.dto.MarketSyncMarketReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncReportRequest;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationSyncRow;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketCatalogRecoveryTest {

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;

	private static final MarketType MARKET = MarketType.SMART_STORE;

	@Test
	@DisplayName("D-226: 식별자를 잃은 등록행을 마켓에서 찾으면 식별자를 되찾아 동기 상태로 복구한다")
	void orphanRow_recoversIdentifiersFromCatalog() {
		MarketRegistration reg = emptyIdentifierRegistration();
		catalogHaving("SB001", Map.of("originProductNo", "111", "channelProductNo", "222"));

		MarketSyncMarketReport report = run(true);

		assertThat(report.recoveredIdentifiers()).isEqualTo(1);
		assertThat(reg.identifier("originProductNo")).isEqualTo("111");
		assertThat(reg.identifier("channelProductNo")).isEqualTo("222");
		assertThat(reg.getIsSynced()).isTrue();
		verify(marketRegistrationRepository).save(reg);
	}

	@Test
	@DisplayName("D-226: persist=false 면 복구하지 않는다 — 읽기 전용 리포트를 깨지 않는다")
	void persistOff_recoversNothing() {
		MarketRegistration reg = emptyIdentifierRegistration();
		catalogHaving("SB001", Map.of("originProductNo", "111"));

		MarketSyncMarketReport report = run(false);

		assertThat(report.recoveredIdentifiers()).isZero();
		assertThat(reg.hasIdentifiers()).isFalse();
		verify(marketRegistrationRepository, never()).save(any());
	}

	@Test
	@DisplayName("D-226: 마켓에 없으면 복구하지 않는다 — 식별자를 지어내지 않는다")
	void notOnMarket_recoversNothing() {
		MarketRegistration reg = emptyIdentifierRegistration();
		catalogHaving("OTHER", Map.of("originProductNo", "999"));

		MarketSyncMarketReport report = run(true);

		assertThat(report.recoveredIdentifiers()).isZero();
		assertThat(reg.hasIdentifiers()).isFalse();
	}

	@Test
	@DisplayName("D-226: 식별자가 없는 행의 부재는 NEVER_SYNCED 다 — 있었던 적이 없으니 삭제가 아니다")
	void absentOrphan_isNeverSyncedNotDeleted() {
		MarketRegistration reg = emptyIdentifierRegistration();
		MarketClient client = catalogHaving("OTHER", Map.of("originProductNo", "999"));
		when(client.supportsSingleLookup()).thenReturn(true);
		when(client.fetchBySellerCode("SB001")).thenReturn(Optional.empty());

		MarketSyncMarketReport report = run(true);

		assertThat(report.persistedAbsent()).isEqualTo(1);
		assertThat(reg.getUnsyncReason()).isEqualTo(UnsyncReason.NEVER_SYNCED);
	}

	private MarketRegistration emptyIdentifierRegistration() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MARKET).marketIdentifiers("{}").build();
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MARKET))
			.thenReturn(Optional.of(reg));
		return reg;
	}

	private MarketClient catalogHaving(String sellerCode, Map<String, String> identifiers) {
		when(marketRegistrationRepository.findSyncRowsByMarketType(MARKET))
			.thenReturn(List.of(row(1L, "SB001", "{}")));
		MarketClient client = mock(MarketClient.class);
		when(marketClientRouter.hasClient(MARKET)).thenReturn(true);
		when(marketClientRouter.getClient(MARKET)).thenReturn(client);
		when(client.fetchCatalog(anyLong()))
			.thenReturn(List.of(new MarketCatalogEntry(sellerCode, identifiers, "SALE")));
		return client;
	}

	private MarketSyncMarketReport run(boolean persist) {
		return new MarketCatalogReconciliationService(marketRegistrationRepository, marketClientRouter)
			.reconcile(MarketSyncReportRequest.of(List.of(MARKET), 20, true, 100, 0L, false, 0, persist))
			.markets().get(0);
	}

	private MarketRegistrationSyncRow row(Long productId, String sbCode, String identifiers) {
		return new MarketRegistrationSyncRow() {
			@Override
			public Long getProductId() {
				return productId;
			}

			@Override
			public String getSbCode() {
				return sbCode;
			}

			@Override
			public String getMarketIdentifiers() {
				return identifiers;
			}

			@Override
			public BigDecimal getLocalSalePrice() {
				return null;
			}
		};
	}
}
