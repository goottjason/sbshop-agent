package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.market.dto.MarketLiveInventoryReport;
import com.sbshop.agent.core.application.market.dto.MarketLivePriceSample;
import com.sbshop.agent.core.application.market.dto.MarketLiveStatus;
import com.sbshop.agent.core.application.market.dto.MarketSyncMarketReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncReportRequest;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPrice;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPriceMiss;
import com.sbshop.agent.core.domain.market.client.dto.MarketLiveOption;
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
class MarketCatalogReconciliationLiveInventoryTest {

	private static final String IDENTIFIERS = "{\"sellerProductId\":\"14813281569\",\"vendorItemId\":\"87763025801\"}";

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;

	@Test
	@DisplayName("옵션을 끄면 기본 동작 불변 — 실판매 블록이 없고 옵션·초안 조회를 단 한 번도 하지 않는다")
	void disabledByDefault_noLiveCallsAtAll() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();

		MarketSyncMarketReport report = reportOf(false, 200, 0L);

		assertThat(report.liveInventory()).isNull();
		verify(client, never()).fetchLiveOption(anyString());
		verify(client, never()).fetchDraftSalePrice(anyString());
	}

	@Test
	@DisplayName("실판매중: onSale=true 면 ON_SALE 로 집계하고 3값을 나란히 싣는다")
	void onSale_recordsThreePrices() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice("14813281569")).thenReturn(MarketDraftPrice.of(61400));
		when(client.fetchLiveOption("87763025801"))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 999, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.candidates()).isEqualTo(1);
		assertThat(live.examined()).isEqualTo(1);
		assertThat(live.statusCounts()).containsEntry(MarketLiveStatus.ON_SALE, 1);
		assertThat(live.priceComparable()).isEqualTo(1);
		assertThat(live.priceDiverged()).isEqualTo(1);
		assertThat(live.localVsLiveDiverged()).isZero();
		assertThat(live.draftVsLiveDiverged()).isEqualTo(1);
		assertThat(live.draftAboveLive()).isEqualTo(1);
		assertThat(live.draftBelowLive()).isZero();

		MarketLivePriceSample sample = live.samples().get(0);
		assertThat(sample.sbCode()).isEqualTo("SB001");
		assertThat(sample.sellerProductId()).isEqualTo("14813281569");
		assertThat(sample.optionId()).isEqualTo("87763025801");
		assertThat(sample.localPolicyPrice()).isEqualByComparingTo("50100");
		assertThat(sample.draftSalePrice()).isEqualTo(61400);
		assertThat(sample.liveSalePrice()).isEqualTo(50100);
		assertThat(sample.liveStock()).isEqualTo(999);
		assertThat(sample.status()).isEqualTo(MarketLiveStatus.ON_SALE);
	}

	@Test
	@DisplayName("3값이 모두 같으면 어긋남 0 이고 샘플에도 담지 않는다")
	void allEqual_notSampled() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(50100));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 10, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.priceAllEqual()).isEqualTo(1);
		assertThat(live.priceDiverged()).isZero();
		assertThat(live.samples()).isEmpty();
	}

	@Test
	@DisplayName("로컬 정책가가 실판매가와 다르면 localVsLiveDiverged 로 잡는다")
	void localVsLiveDivergence() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("48000")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(50100));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 10, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.localVsLiveDiverged()).isEqualTo(1);
		assertThat(live.draftVsLiveDiverged()).isZero();
		assertThat(live.priceDiverged()).isEqualTo(1);
	}

	@Test
	@DisplayName("판매중지: onSale=false 면 NOT_ON_SALE 로 분류한다")
	void notOnSale() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(50100));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 0, false)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.statusCounts()).containsEntry(MarketLiveStatus.NOT_ON_SALE, 1);
		assertThat(live.samples().get(0).status()).isEqualTo(MarketLiveStatus.NOT_ON_SALE);
	}

	@Test
	@DisplayName("조회 실패는 UNDETERMINED 이고 미노출로 단정하지 않는다")
	void lookupFailure_isUndeterminedNotAbsent() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(50100));
		when(client.fetchLiveOption(anyString())).thenThrow(new RuntimeException("500 Internal Server Error"));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.statusCounts()).containsEntry(MarketLiveStatus.UNDETERMINED, 1);
		assertThat(live.lookupFailed()).isEqualTo(1);
		assertThat(live.statusCounts()).doesNotContainEntry(MarketLiveStatus.NOT_ON_SALE, 1);
		assertThat(live.samples().get(0).note()).contains("미판정");
		assertThat(live.samples().get(0).note()).doesNotContain("미노출");
	}

	@Test
	@DisplayName("옵션ID 가 없으면 UNDETERMINED 이고 옵션 조회를 호출하지 않는다")
	void noOptionId_isUndeterminedAndSkipsCall() {
		localRows(row(1L, "SB001", "{\"sellerProductId\":\"14813281569\"}", new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(50100));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.noOptionId()).isEqualTo(1);
		assertThat(live.statusCounts()).containsEntry(MarketLiveStatus.UNDETERMINED, 1);
		verify(client, never()).fetchLiveOption(anyString());
	}

	@Test
	@DisplayName("옵션이 부재(빈 값)면 UNDETERMINED 로 두되 조회 실패와 구분해 센다")
	void optionAbsent_separatedFromFailure() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(50100));
		when(client.fetchLiveOption(anyString())).thenReturn(Optional.empty());

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.optionAbsent()).isEqualTo(1);
		assertThat(live.lookupFailed()).isZero();
		assertThat(live.statusCounts()).containsEntry(MarketLiveStatus.UNDETERMINED, 1);
	}

	@Test
	@DisplayName("초안가 조회가 실패해도 그 건만 미상으로 두고 실판매 판별은 계속한다")
	void draftFailure_doesNotAbortRow() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenThrow(new RuntimeException("503"));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 5, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftUnknown()).isEqualTo(1);
		assertThat(live.priceComparable()).isZero();
		assertThat(live.statusCounts()).containsEntry(MarketLiveStatus.ON_SALE, 1);
	}

	@Test
	@DisplayName("상한을 넘으면 잔여는 조회하지 않고 잘림을 알린다")
	void liveLimit_truncates() {
		localRows(
			row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")),
			row(2L, "SB002", IDENTIFIERS, new BigDecimal("50100")),
			row(3L, "SB003", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(50100));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 1, true)));

		MarketLiveInventoryReport live = reportOf(true, 2, 0L).liveInventory();

		assertThat(live.candidates()).isEqualTo(3);
		assertThat(live.examined()).isEqualTo(2);
		assertThat(live.truncated()).isTrue();
		assertThat(live.warnings()).anyMatch(w -> w.contains("liveLimit"));
		verify(client, times(2)).fetchLiveOption(anyString());
	}

	@Test
	@DisplayName("옵션 조회를 지원하지 않는 마켓은 실판매 블록을 만들지 않고 경고만 남긴다")
	void unsupportedMarket_leavesBlockNull() {
		when(marketRegistrationRepository.findSyncRowsByMarketType(MarketType.SMART_STORE))
			.thenReturn(List.of(row(1L, "SB001", "{\"channelProductNo\":\"1\"}", new BigDecimal("1000"))));
		MarketClient client = mock(MarketClient.class);
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(client);
		when(client.supportsLiveOptionLookup()).thenReturn(false);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB001", Map.of("channelProductNo", "1"), "SALE")));

		MarketSyncMarketReport report = new MarketCatalogReconciliationService(
			marketRegistrationRepository, marketClientRouter)
			.reconcile(MarketSyncReportRequest.of(List.of(MarketType.SMART_STORE), 200, false, 0, 0L, true, 100))
			.markets().get(0);

		assertThat(report.liveInventory()).isNull();
		assertThat(report.warnings()).anyMatch(w -> w.contains("옵션 실판매 조회"));
		verify(client, never()).fetchLiveOption(anyString());
	}

	@Test
	@DisplayName("경계: 초안가 < 실판매가면 draftBelowLive 만 오르고 draftAboveLive 는 0 이다")
	void draftBelowLive_boundary() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(48000));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftBelowLive()).isEqualTo(1);
		assertThat(live.draftAboveLive()).isZero();
		assertThat(live.draftVsLiveDiverged()).isEqualTo(1);
		assertThat(live.priceDiverged()).isEqualTo(1);
	}

	@Test
	@DisplayName("경계: 초안가 == 실판매가면 above·below 둘 다 0 이고 어긋남으로도 세지 않는다")
	void draftEqualsLive_boundary() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(50100));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftAboveLive()).isZero();
		assertThat(live.draftBelowLive()).isZero();
		assertThat(live.draftVsLiveDiverged()).isZero();
	}

	@Test
	@DisplayName("초안가 미상 사유를 구분해 집계한다 — items 빈 배열")
	void draftMissReason_emptyItems() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString()))
			.thenReturn(MarketDraftPrice.missing(MarketDraftPriceMiss.EMPTY_ITEMS));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftUnknown()).isEqualTo(1);
		assertThat(live.draftMissReasons()).containsEntry(MarketDraftPriceMiss.EMPTY_ITEMS, 1);
	}

	@Test
	@DisplayName("초안가 미상 사유를 구분해 집계한다 — 구조 불일치(items 필드 부재)")
	void draftMissReason_structureMismatch() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString()))
			.thenReturn(MarketDraftPrice.missing(MarketDraftPriceMiss.NO_ITEMS_FIELD));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftMissReasons()).containsEntry(MarketDraftPriceMiss.NO_ITEMS_FIELD, 1);
	}

	@Test
	@DisplayName("초안가 미상 사유를 구분해 집계한다 — 조회 실패는 LOOKUP_FAILED 로 분리된다")
	void draftMissReason_lookupFailed() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenThrow(new RuntimeException("503"));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftMissReasons()).containsEntry(MarketDraftPriceMiss.LOOKUP_FAILED, 1);
		assertThat(live.draftMissReasons()).doesNotContainKey(MarketDraftPriceMiss.EMPTY_ITEMS);
	}

	@Test
	@DisplayName("초안가 미상 사유를 구분해 집계한다 — 로컬에 sellerProductId 가 없으면 호출 없이 NO_SELLER_PRODUCT_ID")
	void draftMissReason_noSellerProductId() {
		localRows(row(1L, "SB001", "{\"vendorItemId\":\"87763025801\"}", new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftMissReasons()).containsEntry(MarketDraftPriceMiss.NO_SELLER_PRODUCT_ID, 1);
		verify(client, never()).fetchDraftSalePrice(anyString());
	}

	@Test
	@DisplayName("초안가 미상이 하나라도 있으면 draftAboveLive 를 하한으로 표시하고 사유를 경고에 남긴다")
	void draftUnknown_marksUnderstated() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString()))
			.thenReturn(MarketDraftPrice.missing(MarketDraftPriceMiss.EMPTY_ITEMS));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftAboveLive()).isZero();
		assertThat(live.draftAboveLiveUnderstated()).isTrue();
		assertThat(live.warnings()).anyMatch(w -> w.contains("EMPTY_ITEMS=1"));
		assertThat(live.warnings()).anyMatch(w -> w.contains("'롤백 위험 없음'이 아닙니다"));
	}

	@Test
	@DisplayName("초안가가 계통적으로 안 읽히면 측정 신뢰 불가로 판정하고 오독 금지를 명시한다")
	void draftUnknown_marksMeasurementUnreliable() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString()))
			.thenReturn(MarketDraftPrice.missing(MarketDraftPriceMiss.NO_ITEMS_FIELD));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.priceComparable()).isZero();
		assertThat(live.draftMeasurementUnreliable()).isTrue();
		assertThat(live.warnings())
			.anyMatch(w -> w.contains("초안가 측정 신뢰 불가") && w.contains("'위험 없음'으로 읽지 말 것"));
	}

	@Test
	@DisplayName("미상률이 10% 미만이면 측정 신뢰 불가로는 판정하지 않는다 — 하한 표시는 그대로 남는다")
	void draftUnknown_belowRatioStaysReliable() {
		MarketRegistrationSyncRow[] rows = new MarketRegistrationSyncRow[20];
		for (int i = 0; i < 20; i++) {
			rows[i] = row((long)(i + 1), "SB" + i, IDENTIFIERS, new BigDecimal("50100"));
		}
		localRows(rows);
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString()))
			.thenReturn(MarketDraftPrice.missing(MarketDraftPriceMiss.EMPTY_ITEMS))
			.thenReturn(MarketDraftPrice.of(50100));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftUnknown()).isEqualTo(1);
		assertThat(live.priceComparable()).isEqualTo(19);
		assertThat(live.draftMeasurementUnreliable()).isFalse();
		assertThat(live.draftAboveLiveUnderstated()).isTrue();
	}

	@Test
	@DisplayName("초안가가 전부 읽히면 하한 표시도 신뢰 불가 판정도 걸지 않는다")
	void draftFullyMeasured_noTrustWarnings() {
		localRows(row(1L, "SB001", IDENTIFIERS, new BigDecimal("50100")));
		MarketClient client = coupangClient();
		when(client.fetchDraftSalePrice(anyString())).thenReturn(MarketDraftPrice.of(61400));
		when(client.fetchLiveOption(anyString()))
			.thenReturn(Optional.of(new MarketLiveOption("87763025801", 50100, 3, true)));

		MarketLiveInventoryReport live = reportOf(true, 200, 0L).liveInventory();

		assertThat(live.draftUnknown()).isZero();
		assertThat(live.draftMissReasons()).isEmpty();
		assertThat(live.draftAboveLiveUnderstated()).isFalse();
		assertThat(live.draftMeasurementUnreliable()).isFalse();
		assertThat(live.draftAboveLive()).isEqualTo(1);
	}

	private MarketSyncMarketReport reportOf(boolean liveInventory, int liveLimit, long throttleMs) {
		return new MarketCatalogReconciliationService(marketRegistrationRepository, marketClientRouter)
			.reconcile(MarketSyncReportRequest.of(List.of(MarketType.COUPANG), 200, false, 0, throttleMs,
				liveInventory, liveLimit))
			.markets().get(0);
	}

	private MarketClient coupangClient() {
		MarketClient client = mock(MarketClient.class);
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(client);
		when(client.supportsLiveOptionLookup()).thenReturn(true);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry(null, Map.of("sellerProductId", "14813281569"), "임시저장")));
		return client;
	}

	private void localRows(MarketRegistrationSyncRow... rows) {
		when(marketRegistrationRepository.findSyncRowsByMarketType(MarketType.COUPANG)).thenReturn(List.of(rows));
	}

	private MarketRegistrationSyncRow row(Long productId, String sbCode, String identifiers, BigDecimal salePrice) {
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
				return salePrice;
			}
		};
	}
}
