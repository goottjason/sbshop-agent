package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.market.dto.MarketSyncBucket;
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
class MarketCatalogPersistAbsenceTest {

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;

	private static final MarketType MARKET = MarketType.SMART_STORE;

	@Test
	@DisplayName("D-227: 단건 조회로도 없으면 DELETED_ON_MARKET 을 기록한다 — 부재가 확정된 경우만")
	void deepConfirmedAbsence_isPersisted() {
		MarketRegistration reg = registration();
		MarketClient client = catalogWithout("SB001");
		when(client.supportsSingleLookup()).thenReturn(true);
		when(client.fetchBySellerCode("SB001")).thenReturn(Optional.empty());

		MarketSyncMarketReport report = run(true, true);

		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isEqualTo(1);
		assertThat(report.persistedAbsent()).isEqualTo(1);
		assertThat(reg.getIsSynced()).isFalse();
		assertThat(reg.getUnsyncReason()).isEqualTo(UnsyncReason.DELETED_ON_MARKET);
		verify(marketRegistrationRepository).save(reg);
	}

	@Test
	@DisplayName("D-227: 카탈로그에만 없는 것은 기록하지 않는다 — 목록 누락일 수 있어 증거가 부족하다")
	void catalogOnlyAbsence_isNotPersisted() {
		MarketRegistration reg = registration();
		MarketClient client = catalogWithout("SB001");
		when(client.supportsSingleLookup()).thenReturn(false);

		MarketSyncMarketReport report = run(true, true);

		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isEqualTo(1);
		assertThat(report.persistedAbsent()).isZero();
		assertThat(reg.getUnsyncReason()).isNull();
		verify(marketRegistrationRepository, never()).save(any());
	}

	@Test
	@DisplayName("D-227: 단건 조회가 실패하면 기록하지 않는다 — 조회 실패는 부재가 아니다")
	void deepLookupFailure_isNotPersisted() {
		MarketRegistration reg = registration();
		MarketClient client = catalogWithout("SB001");
		when(client.supportsSingleLookup()).thenReturn(true);
		when(client.fetchBySellerCode("SB001")).thenThrow(new RuntimeException("Read timed out"));

		MarketSyncMarketReport report = run(true, true);

		assertThat(report.persistedAbsent()).isZero();
		assertThat(reg.getUnsyncReason()).isNull();
		verify(marketRegistrationRepository, never()).save(any());
	}

	@Test
	@DisplayName("D-227: persist=false 가 기본이다 — 읽기 전용 리포트를 깨지 않는다")
	void persistOff_writesNothing() {
		MarketRegistration reg = registration();
		MarketClient client = catalogWithout("SB001");
		when(client.supportsSingleLookup()).thenReturn(true);
		when(client.fetchBySellerCode("SB001")).thenReturn(Optional.empty());

		MarketSyncMarketReport report = run(true, false);

		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isEqualTo(1);
		assertThat(report.persistedAbsent()).isZero();
		assertThat(reg.getUnsyncReason()).isNull();
		verify(marketRegistrationRepository, never()).save(any());
	}

	@Test
	@DisplayName("D-227: persist=true 인데 deep=false 면 경고를 남기고 아무것도 쓰지 않는다")
	void persistWithoutDeep_warnsAndWritesNothing() {
		MarketRegistration reg = registration();
		catalogWithout("SB001");

		MarketSyncMarketReport report = run(false, true);

		assertThat(report.persistedAbsent()).isZero();
		assertThat(reg.getUnsyncReason()).isNull();
		assertThat(report.warnings()).anyMatch(w -> w.contains("deep=false"));
	}

	@Test
	@DisplayName("D-227: 이미 DELETED_ON_MARKET 인 행은 다시 쓰지 않는다 — 멱등")
	void alreadyDeleted_isNotRewritten() {
		MarketRegistration reg = registration();
		reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		MarketClient client = catalogWithout("SB001");
		when(client.supportsSingleLookup()).thenReturn(true);
		when(client.fetchBySellerCode("SB001")).thenReturn(Optional.empty());

		MarketSyncMarketReport report = run(true, true);

		assertThat(report.persistedAbsent()).isZero();
		verify(marketRegistrationRepository, never()).save(any());
	}

	private MarketRegistration registration() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MARKET)
			.marketIdentifiers("{\"originProductNo\":\"111\"}").build();
		reg.markSynced();
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MARKET))
			.thenReturn(Optional.of(reg));
		return reg;
	}

	private MarketClient catalogWithout(String sbCode) {
		when(marketRegistrationRepository.findSyncRowsByMarketType(MARKET))
			.thenReturn(List.of(row(1L, sbCode, "{\"originProductNo\":\"111\"}")));
		MarketClient client = mock(MarketClient.class);
		when(marketClientRouter.hasClient(MARKET)).thenReturn(true);
		when(marketClientRouter.getClient(MARKET)).thenReturn(client);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("OTHER", java.util.Map.of("originProductNo", "999"), "SALE")));
		return client;
	}

	private MarketSyncMarketReport run(boolean deep, boolean persist) {
		MarketCatalogReconciliationService service = new MarketCatalogReconciliationService(
			marketRegistrationRepository, marketClientRouter);
		return service.reconcile(MarketSyncReportRequest.of(List.of(MARKET), 20, deep, 100, 0L, false, 0, persist))
			.markets().get(0);
	}

	private int count(MarketSyncMarketReport report, MarketSyncBucket bucket) {
		return report.bucketCounts().getOrDefault(bucket, 0);
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
