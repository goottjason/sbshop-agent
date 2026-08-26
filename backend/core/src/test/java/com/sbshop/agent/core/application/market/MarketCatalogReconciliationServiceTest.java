package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.market.dto.MarketSyncBucket;
import com.sbshop.agent.core.application.market.dto.MarketSyncIdentifierDiff;
import com.sbshop.agent.core.application.market.dto.MarketSyncMarketReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncOutcome;
import com.sbshop.agent.core.application.market.dto.MarketSyncReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncReportRequest;
import com.sbshop.agent.core.application.market.dto.MarketSyncSample;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationSyncRow;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.util.ArrayList;
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
class MarketCatalogReconciliationServiceTest {

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;

	@Test
	@DisplayName("① 양쪽에 있고 식별자가 같으면 MATCHED — SB코드로 조인한다")
	void matched_whenIdentifiersEqual() {
		localRows(MarketType.SMART_STORE,
			row(1L, "SB001", "{\"originProductNo\":\"111\",\"channelProductNo\":\"222\"}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB001", Map.of("originProductNo", "111", "channelProductNo", "222"), "SALE")));

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(report.outcome()).isEqualTo(MarketSyncOutcome.COMPLETED);
		assertThat(count(report, MarketSyncBucket.MATCHED)).isEqualTo(1);
		assertThat(count(report, MarketSyncBucket.IDENTIFIER_MISMATCH)).isZero();
		assertThat(report.matchedBySbCode()).isEqualTo(1);
	}

	@Test
	@DisplayName("② 저장된 식별자 값이 마켓과 다르면 IDENTIFIER_MISMATCH — 어느 키가 어떻게 다른지 기록한다")
	void identifierMismatch_recordsDifferingKey() {
		localRows(MarketType.SMART_STORE,
			row(1L, "SB001", "{\"originProductNo\":\"111\",\"channelProductNo\":\"999\"}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB001", Map.of("originProductNo", "111", "channelProductNo", "222"), "SALE")));

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(count(report, MarketSyncBucket.IDENTIFIER_MISMATCH)).isEqualTo(1);
		List<MarketSyncSample> samples = report.samples().get(MarketSyncBucket.IDENTIFIER_MISMATCH);
		assertThat(samples).hasSize(1);
		assertThat(samples.get(0).sbCode()).isEqualTo("SB001");
		assertThat(samples.get(0).differences())
			.containsExactly(new MarketSyncIdentifierDiff("channelProductNo", "999", "222"));
	}

	@Test
	@DisplayName("③ 마켓에만 있으면 MISSING_LOCAL")
	void missingLocal_whenMarketOnly() {
		localRows(MarketType.SMART_STORE);
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB404", Map.of("originProductNo", "111"), "SALE")));

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(count(report, MarketSyncBucket.MISSING_LOCAL)).isEqualTo(1);
		assertThat(report.samples().get(MarketSyncBucket.MISSING_LOCAL).get(0).sbCode()).isEqualTo("SB404");
	}

	@Test
	@DisplayName("③-b 등록행은 있으나 식별자가 비어 있으면 MISSING_LOCAL로 분류한다")
	void missingLocal_whenLocalHasNoIdentifiers() {
		localRows(MarketType.SMART_STORE, row(1L, "SB001", "{}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB001", Map.of("originProductNo", "111"), "SALE")));

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(count(report, MarketSyncBucket.MISSING_LOCAL)).isEqualTo(1);
		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isZero();
		assertThat(report.localWithoutIdentifiers()).isEqualTo(1);
	}

	@Test
	@DisplayName("④ 우리에만 있고 마켓 카탈로그에 없으면 STALE_LOCAL")
	void staleLocal_whenLocalOnly() {
		localRows(MarketType.SMART_STORE, row(7L, "SB007", "{\"originProductNo\":\"777\"}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB404", Map.of("originProductNo", "404"), "SALE")));

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isEqualTo(1);
		MarketSyncSample sample = report.samples().get(MarketSyncBucket.STALE_LOCAL).get(0);
		assertThat(sample.sbCode()).isEqualTo("SB007");
		assertThat(sample.productId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("⑤ fetchCatalog가 null이면 UNSUPPORTED — 로컬 집계는 그대로 보고한다")
	void unsupported_whenCatalogNull() {
		localRows(MarketType.GMARKET, row(3L, "SB003", "{\"gmarket_goodsNo\":\"g1\"}"));
		MarketClient client = clientFor(MarketType.GMARKET);
		when(client.fetchCatalog(anyLong())).thenReturn(null);

		MarketSyncMarketReport report = reportOf(MarketType.GMARKET);

		assertThat(report.outcome()).isEqualTo(MarketSyncOutcome.UNSUPPORTED);
		assertThat(report.localTotal()).isEqualTo(1);
		assertThat(report.bucketCounts().values()).allMatch(v -> v == 0);
	}

	@Test
	@DisplayName("⑥ 한 마켓이 예외로 실패해도 사유를 담고 다른 마켓 처리는 계속한다")
	void failedMarketDoesNotStopOtherMarkets() {
		localRows(MarketType.ELEVEN_STREET, row(1L, "SB001", "{\"prdNo\":\"11\"}"));
		localRows(MarketType.CAFE24, row(1L, "SB001", "{\"product_no\":\"5\"}"));
		MarketClient eleven = clientFor(MarketType.ELEVEN_STREET);
		when(eleven.fetchCatalog(anyLong()))
			.thenThrow(new IllegalStateException("-997 등록된 API 정보가 존재하지 않습니다"));
		MarketClient cafe24 = clientFor(MarketType.CAFE24);
		when(cafe24.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB001", Map.of("product_no", "5"), "T")));

		MarketSyncReport report = service().reconcile(
			MarketSyncReportRequest.of(List.of(MarketType.ELEVEN_STREET, MarketType.CAFE24), 20, false, 0, 0L));

		MarketSyncMarketReport elevenReport = pick(report, MarketType.ELEVEN_STREET);
		assertThat(elevenReport.outcome()).isEqualTo(MarketSyncOutcome.FAILED);
		assertThat(elevenReport.failureReason()).contains("-997");
		MarketSyncMarketReport cafeReport = pick(report, MarketType.CAFE24);
		assertThat(cafeReport.outcome()).isEqualTo(MarketSyncOutcome.COMPLETED);
		assertThat(count(cafeReport, MarketSyncBucket.MATCHED)).isEqualTo(1);
	}

	@Test
	@DisplayName("⑦ SB코드도 식별자도 없는 등록행은 STALE_LOCAL이 아니라 UNJOINABLE_LOCAL이다")
	void unjoinableLocal_whenNoSbCodeAndNoIdentifier() {
		localRows(MarketType.SMART_STORE, row(9L, null, "{}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB404", Map.of("originProductNo", "404"), "SALE")));

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(count(report, MarketSyncBucket.UNJOINABLE_LOCAL)).isEqualTo(1);
		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isZero();
		assertThat(report.localWithSbCode()).isZero();
	}

	@Test
	@DisplayName("⑦-b SB코드가 없어도 식별자가 있으면 식별자로 조인해 MATCHED가 된다")
	void matchedByIdentifier_whenLocalSbCodeMissing() {
		localRows(MarketType.SMART_STORE, row(9L, null, "{\"originProductNo\":\"111\"}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB999", Map.of("originProductNo", "111"), "SALE")));

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(count(report, MarketSyncBucket.MATCHED)).isEqualTo(1);
		assertThat(report.matchedByIdentifier()).isEqualTo(1);
	}

	@Test
	@DisplayName("⑧ 쿠팡처럼 sellerCode=null인 카탈로그는 sellerProductId로 대체 조인한다")
	void coupangFallbackJoin_bySellerProductId() {
		localRows(MarketType.COUPANG,
			row(1L, "SB001", "{\"sellerProductId\":\"1001\",\"productId\":\"2001\"}"),
			row(2L, "SB002", "{\"sellerProductId\":\"1002\",\"productId\":\"2002\"}"));
		MarketClient client = clientFor(MarketType.COUPANG);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry(null, Map.of("sellerProductId", "1001", "productId", "2001"), "APPROVED"),
			new MarketCatalogEntry(null, Map.of("sellerProductId", "1003", "productId", "2003"), "DELETED")));

		MarketSyncMarketReport report = reportOf(MarketType.COUPANG);

		assertThat(count(report, MarketSyncBucket.MATCHED)).isEqualTo(1);
		assertThat(count(report, MarketSyncBucket.MISSING_LOCAL)).isEqualTo(1);
		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isEqualTo(1);
		assertThat(report.matchedByIdentifier()).isEqualTo(1);
		assertThat(report.samples().get(MarketSyncBucket.MISSING_LOCAL).get(0).marketIdentifiers())
			.containsEntry("sellerProductId", "1003");
	}

	@Test
	@DisplayName("⑧-b sellerCode가 우리 SB코드와 다르면 sellerCode 차이를 불일치로 기록한다")
	void sellerCodeDisagreement_isRecordedAsMismatch() {
		localRows(MarketType.COUPANG, row(1L, "SB001", "{\"sellerProductId\":\"1001\"}"));
		MarketClient client = clientFor(MarketType.COUPANG);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB-OLD", Map.of("sellerProductId", "1001"), "APPROVED")));

		MarketSyncMarketReport report = reportOf(MarketType.COUPANG);

		assertThat(count(report, MarketSyncBucket.IDENTIFIER_MISMATCH)).isEqualTo(1);
		assertThat(report.samples().get(MarketSyncBucket.IDENTIFIER_MISMATCH).get(0).differences())
			.contains(new MarketSyncIdentifierDiff("sellerCode", "SB001", "SB-OLD"));
	}

	@Test
	@DisplayName("마켓 원문 status 분포를 집계한다 — D-207·D-209 전수 파악용")
	void aggregatesMarketStatusDistribution() {
		localRows(MarketType.SMART_STORE);
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("A", Map.of("originProductNo", "1"), "SALE"),
			new MarketCatalogEntry("B", Map.of("originProductNo", "2"), "SUSPENSION"),
			new MarketCatalogEntry("C", Map.of("originProductNo", "3"), "SUSPENSION"),
			new MarketCatalogEntry("D", Map.of("originProductNo", "4"), null)));

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(report.marketStatusCounts()).containsEntry("SUSPENSION", 2).containsEntry("SALE", 1);
		assertThat(report.marketStatusCounts().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(4);
	}

	@Test
	@DisplayName("샘플은 limit까지만 담고 건수는 전량을 센다")
	void samplesRespectLimitWhileCountsAreComplete() {
		localRows(MarketType.SMART_STORE);
		List<MarketCatalogEntry> entries = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			entries.add(new MarketCatalogEntry("SB" + i, Map.of("originProductNo", String.valueOf(i)), "SALE"));
		}
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(entries);

		MarketSyncMarketReport report = service()
			.reconcile(MarketSyncReportRequest.of(List.of(MarketType.SMART_STORE), 3, false, 0, 0L))
			.markets().get(0);

		assertThat(count(report, MarketSyncBucket.MISSING_LOCAL)).isEqualTo(25);
		assertThat(report.samples().get(MarketSyncBucket.MISSING_LOCAL)).hasSize(3);
	}

	@Test
	@DisplayName("성능: 마켓 카탈로그는 상품 수와 무관하게 1회만 조회한다")
	void fetchesCatalogOnlyOnce() {
		localRows(MarketType.SMART_STORE,
			row(1L, "SB001", "{\"originProductNo\":\"1\"}"),
			row(2L, "SB002", "{\"originProductNo\":\"2\"}"),
			row(3L, "SB003", "{\"originProductNo\":\"3\"}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB001", Map.of("originProductNo", "1"), "SALE")));

		reportOf(MarketType.SMART_STORE);

		verify(client, times(1)).fetchCatalog(anyLong());
		verify(client, never()).fetchBySellerCode(anyString());
	}

	@Test
	@DisplayName("deep=true면 STALE_LOCAL 후보만 단건 조회로 확인하고, 마켓에 실재하면 재분류한다")
	void deepModeConfirmsStaleLocalOnly() {
		localRows(MarketType.COUPANG,
			row(1L, "SB001", "{\"sellerProductId\":\"1001\"}"),
			row(2L, "SB002", "{\"sellerProductId\":\"1002\"}"));
		MarketClient client = clientFor(MarketType.COUPANG);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry(null, Map.of("sellerProductId", "1001"), "APPROVED")));
		when(client.supportsSingleLookup()).thenReturn(true);
		when(client.fetchBySellerCode("SB002")).thenReturn(
			Optional.of(new MarketCatalogEntry("SB002", Map.of("sellerProductId", "1002"), "APPROVED")));

		MarketSyncMarketReport report = service()
			.reconcile(MarketSyncReportRequest.of(List.of(MarketType.COUPANG), 20, true, 10, 0L))
			.markets().get(0);

		verify(client, times(1)).fetchBySellerCode(anyString());
		verify(client, never()).fetchBySellerCode("SB001");
		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isZero();
		assertThat(count(report, MarketSyncBucket.MATCHED)).isEqualTo(2);
		assertThat(report.deepLookups()).isEqualTo(1);
	}

	@Test
	@DisplayName("마켓이 같은 로컬 행에 두 번 매칭되면 DUPLICATE_MARKET으로 분리한다")
	void duplicateMarketListingIsSeparated() {
		localRows(MarketType.CAFE24, row(1L, "SB001", "{\"product_no\":\"5\"}"));
		MarketClient client = clientFor(MarketType.CAFE24);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB001", Map.of("product_no", "5"), "T"),
			new MarketCatalogEntry("SB001", Map.of("product_no", "6"), "T")));

		MarketSyncMarketReport report = reportOf(MarketType.CAFE24);

		assertThat(count(report, MarketSyncBucket.MATCHED)).isEqualTo(1);
		assertThat(count(report, MarketSyncBucket.DUPLICATE_MARKET)).isEqualTo(1);
	}

	@Test
	@DisplayName("클라이언트가 등록되지 않은 마켓은 예외 없이 UNSUPPORTED로 보고한다")
	void unsupported_whenNoClientRegistered() {
		localRows(MarketType.AUCTION, row(1L, "SB001", "{\"auction_goodsNo\":\"a1\"}"));
		when(marketClientRouter.hasClient(MarketType.AUCTION)).thenReturn(false);

		MarketSyncMarketReport report = reportOf(MarketType.AUCTION);

		assertThat(report.outcome()).isEqualTo(MarketSyncOutcome.UNSUPPORTED);
		assertThat(report.failureReason()).isNotBlank();
		assertThat(report.localTotal()).isEqualTo(1);
	}

	@Test
	@DisplayName("마켓 미지정이면 전 마켓을 대조한다")
	void defaultsToAllMarkets() {
		MarketSyncReport report = service().reconcile(MarketSyncReportRequest.of(null, null, null, null, 0L));

		assertThat(report.markets()).hasSize(MarketSyncReportRequest.DEFAULT_MARKETS.size());
		assertThat(report.markets()).noneMatch(m -> m.market().equals(MarketType.UNKNOWN.name()));
	}

	@Test
	@DisplayName("마켓 카탈로그가 0건인데 로컬 등록행이 있으면 분류하지 않고 FAILED로 떨어뜨린다")
	void failed_whenMarketCatalogEmptyButLocalRowsExist() {
		localRows(MarketType.SMART_STORE,
			row(1L, "SB001", "{\"originProductNo\":\"1\"}"),
			row(2L, "SB002", "{\"originProductNo\":\"2\"}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of());

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(report.outcome()).isEqualTo(MarketSyncOutcome.FAILED);
		assertThat(report.failureReason()).contains("0건");
		assertThat(count(report, MarketSyncBucket.STALE_LOCAL)).isZero();
		assertThat(report.localTotal()).isEqualTo(2);
	}

	@Test
	@DisplayName("로컬도 0건이고 마켓도 0건이면 정상 완료로 본다")
	void completed_whenBothSidesEmpty() {
		localRows(MarketType.SMART_STORE);
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of());

		MarketSyncMarketReport report = reportOf(MarketType.SMART_STORE);

		assertThat(report.outcome()).isEqualTo(MarketSyncOutcome.COMPLETED);
		assertThat(report.failureReason()).isNull();
	}

	@Test
	@DisplayName("deep=true라도 단건 조회 미지원 마켓은 조회를 건너뛰고 경고만 남긴다 — 확정 문구를 붙이지 않는다")
	void deepSkipsClientsWithoutSingleLookupSupport() {
		localRows(MarketType.SMART_STORE, row(7L, "SB007", "{\"originProductNo\":\"777\"}"));
		MarketClient client = clientFor(MarketType.SMART_STORE);
		when(client.supportsSingleLookup()).thenReturn(false);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry("SB404", Map.of("originProductNo", "404"), "SALE")));

		MarketSyncMarketReport report = service()
			.reconcile(MarketSyncReportRequest.of(List.of(MarketType.SMART_STORE), 20, true, 10, 0L))
			.markets().get(0);

		verify(client, never()).fetchBySellerCode(anyString());
		assertThat(report.deepLookups()).isZero();
		assertThat(report.warnings()).anyMatch(w -> w.contains("단건 조회"));
		MarketSyncSample sample = report.samples().get(MarketSyncBucket.STALE_LOCAL).get(0);
		assertThat(sample.note()).doesNotContain("단건 조회에도 없습니다");
	}

	@Test
	@DisplayName("deep 단건 조회가 예외로 실패하면 '마켓에 없음'으로 단정하지 않고 미확정으로 표기한다")
	void deepLookupFailureIsNotRecordedAsConfirmedAbsence() {
		localRows(MarketType.COUPANG, row(1L, "SB001", "{\"sellerProductId\":\"1001\"}"));
		MarketClient client = clientFor(MarketType.COUPANG);
		when(client.supportsSingleLookup()).thenReturn(true);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry(null, Map.of("sellerProductId", "9999"), "APPROVED")));
		when(client.fetchBySellerCode("SB001")).thenThrow(new IllegalStateException("타임아웃"));

		MarketSyncMarketReport report = service()
			.reconcile(MarketSyncReportRequest.of(List.of(MarketType.COUPANG), 20, true, 10, 0L))
			.markets().get(0);

		MarketSyncSample sample = report.samples().get(MarketSyncBucket.STALE_LOCAL).get(0);
		assertThat(sample.note()).doesNotContain("단건 조회에도 없습니다");
		assertThat(sample.note()).contains("미확정");
		assertThat(report.warnings()).anyMatch(w -> w.contains("타임아웃"));
	}

	@Test
	@DisplayName("deep 단건 조회가 성공했는데 부재면 그때만 확정 문구를 붙인다")
	void deepLookupMissRecordsConfirmedAbsence() {
		localRows(MarketType.COUPANG, row(1L, "SB001", "{\"sellerProductId\":\"1001\"}"));
		MarketClient client = clientFor(MarketType.COUPANG);
		when(client.supportsSingleLookup()).thenReturn(true);
		when(client.fetchCatalog(anyLong())).thenReturn(List.of(
			new MarketCatalogEntry(null, Map.of("sellerProductId", "9999"), "APPROVED")));
		when(client.fetchBySellerCode("SB001")).thenReturn(Optional.empty());

		MarketSyncMarketReport report = service()
			.reconcile(MarketSyncReportRequest.of(List.of(MarketType.COUPANG), 20, true, 10, 0L))
			.markets().get(0);

		MarketSyncSample sample = report.samples().get(MarketSyncBucket.STALE_LOCAL).get(0);
		assertThat(sample.note()).isEqualTo("마켓 카탈로그에도 단건 조회에도 없습니다");
		assertThat(report.deepLookups()).isEqualTo(1);
	}

	private MarketCatalogReconciliationService service() {
		return new MarketCatalogReconciliationService(marketRegistrationRepository, marketClientRouter);
	}

	private MarketSyncMarketReport reportOf(MarketType market) {
		return service().reconcile(MarketSyncReportRequest.of(List.of(market), 20, false, 0, 0L)).markets().get(0);
	}

	private MarketSyncMarketReport pick(MarketSyncReport report, MarketType market) {
		return report.markets().stream()
			.filter(m -> m.market().equals(market.name()))
			.findFirst()
			.orElseThrow();
	}

	private int count(MarketSyncMarketReport report, MarketSyncBucket bucket) {
		return report.bucketCounts().getOrDefault(bucket, 0);
	}

	private void localRows(MarketType market, MarketRegistrationSyncRow... rows) {
		when(marketRegistrationRepository.findSyncRowsByMarketType(market)).thenReturn(List.of(rows));
	}

	private MarketClient clientFor(MarketType market) {
		MarketClient client = mock(MarketClient.class);
		when(marketClientRouter.hasClient(market)).thenReturn(true);
		when(marketClientRouter.getClient(market)).thenReturn(client);
		return client;
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
