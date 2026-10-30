package com.sbshop.agent.core.application.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.BreakdownItem;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Dimension;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.TimeseriesBucket;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

	@Mock
	DashboardRepository repo;
	DashboardService service;

	@BeforeEach
	void setUp() {
		service = new DashboardService(repo);
	}

	@Test
	@DisplayName("summary.period: 주문수는 distinct order, 금액은 lineItem 합산")
	void summaryAggregates() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 1000, "A"),
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 5000, 3000, 500, "B"),
			row(2, "2026-07-02T01:00:00", MarketType.SMART_STORE, ShippingStatus.SHIPPED, 20000, 12000, 2000, "C")));
		var s = service.summary(LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-31T23:59:59"));
		assertThat(s.period().orderCount()).isEqualTo(2);
		assertThat(s.period().settlementSum()).isEqualTo(35000);
		assertThat(s.period().profitSum()).isEqualTo(35000 - 21000 - 3500);
	}

	@Test
	@DisplayName("timeseries DAY: 빈 날도 0으로 채우고 KST 날짜로 버킷")
	void timeseriesFillsEmptyDays() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 1000, "A")));
		List<TimeseriesBucket> ts = service.timeseries(
			LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-03T23:59:59"), Unit.DAY);
		assertThat(ts).hasSize(3);
		assertThat(ts.get(0).bucketStart()).isEqualTo("2026-07-01");
		assertThat(ts.get(0).orderCount()).isEqualTo(1);
		assertThat(ts.get(1).orderCount()).isEqualTo(0);
		assertThat(ts.get(2).orderCount()).isEqualTo(0);
	}

	@Test
	@DisplayName("timeseries DAY: 말일 밤 주문은 말일 버킷에 담기고 다음 달 버킷을 만들지 않는다")
	void lateNightOrderStaysOnItsDay() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-10-31T20:00:00", MarketType.COUPANG, ShippingStatus.NEW, 10000, 0, 0, "A")));
		List<TimeseriesBucket> ts = service.timeseries(
			LocalDateTime.parse("2026-10-01T00:00:00"), LocalDateTime.parse("2026-10-31T23:59:59"), Unit.DAY);
		assertThat(ts).hasSize(31);
		assertThat(ts.get(30).bucketStart()).isEqualTo("2026-10-31");
		assertThat(ts.get(30).orderCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("breakdown MARKET: 마켓별 distinct 주문수·합계, 라벨 한글")
	void breakdownByMarket() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 0, 0, "A"),
			row(2, "2026-07-02T01:00:00", MarketType.COUPANG, ShippingStatus.SHIPPED, 5000, 0, 0, "B"),
			row(3, "2026-07-02T01:00:00", MarketType.SMART_STORE, ShippingStatus.SHIPPED, 7000, 0, 0, "C")));
		List<BreakdownItem> b = service.breakdown(
			LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-31T23:59:59"), Dimension.MARKET, 10);
		assertThat(b).extracting(BreakdownItem::key).containsExactlyInAnyOrder("COUPANG", "SMART_STORE");
		var coupang = b.stream().filter(x -> x.key().equals("COUPANG")).findFirst().orElseThrow();
		assertThat(coupang.orderCount()).isEqualTo(2);
		assertThat(coupang.settlementSum()).isEqualTo(15000);
	}

	@Test
	@DisplayName("attention: 지연은 결제완료(어제까지 주문)·구매준비(3일 전까지 주문)로 나눠 그리드와 같은 날짜 경계로 센다")
	void attentionAssembles() {
		when(repo.countCustomsIssue(any())).thenReturn(3);
		when(repo.countOutOfStock()).thenReturn(2);
		when(repo.countDelayed(ShippingStatus.NEW, LocalDateTime.parse("2026-07-24T23:59:59"))).thenReturn(5);
		when(repo.countDelayed(ShippingStatus.PREPARING, LocalDateTime.parse("2026-07-22T23:59:59"))).thenReturn(6);
		when(repo.countActiveClaimsInStages(List.of(ClaimStage.REQUESTED, ClaimStage.IN_PROGRESS))).thenReturn(4);
		var a = service.attention(LocalDateTime.parse("2026-07-25T00:10:00"));
		assertThat(a.customsIssue()).isEqualTo(3);
		assertThat(a.outOfStock()).isEqualTo(2);
		assertThat(a.newDelayed()).isEqualTo(5);
		assertThat(a.newDelayedThrough()).isEqualTo(LocalDate.parse("2026-07-24"));
		assertThat(a.preparingDelayed()).isEqualTo(6);
		assertThat(a.preparingDelayedThrough()).isEqualTo(LocalDate.parse("2026-07-22"));
		assertThat(a.returnCancel()).isEqualTo(4);
	}

	@Test
	@DisplayName("summary.period: 취소 클레임·CANCELED 라인은 주문 수·정산·순수익에서 빠진다")
	void summaryExcludesCanceled() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-10-01T10:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 0, "A"),
			claim(row(2, "2026-10-02T10:00:00", MarketType.COUPANG, ShippingStatus.NEW, 5000, 3000, 0, "B"),
				ClaimType.CANCEL, ClaimStage.DONE),
			row(3, "2026-10-03T10:00:00", MarketType.COUPANG, ShippingStatus.CANCELED, 7000, 2000, 0, "C")));
		var p = service.summary(LocalDateTime.parse("2026-10-01T00:00:00"), LocalDateTime.parse("2026-10-31T23:59:59")).period();
		assertThat(p.orderCount()).isEqualTo(1);
		assertThat(p.settlementSum()).isEqualTo(10000);
		assertThat(p.profitSum()).isEqualTo(4000);
	}

	@Test
	@DisplayName("summary.period: 반품은 금액(손실)에 포함되고 주문 수에서 빠져 returnCount·returnProfit으로 따로 나온다")
	void summarySeparatesReturns() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-10-01T10:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 0, "A"),
			claim(row(2, "2026-10-02T10:00:00", MarketType.COUPANG, ShippingStatus.PREPARING, 0, 3000, 0, "B"),
				ClaimType.RETURN, ClaimStage.DONE),
			row(3, "2026-10-03T10:00:00", MarketType.COUPANG, ShippingStatus.RETURNED, 0, 2000, 0, "C")));
		var p = service.summary(LocalDateTime.parse("2026-10-01T00:00:00"), LocalDateTime.parse("2026-10-31T23:59:59")).period();
		assertThat(p.orderCount()).isEqualTo(1);
		assertThat(p.returnCount()).isEqualTo(2);
		assertThat(p.returnProfit()).isEqualTo(-5000);
		assertThat(p.settlementSum()).isEqualTo(10000);
		assertThat(p.profitSum()).isEqualTo(4000 - 5000);
	}

	@Test
	@DisplayName("summary.period: 거부된 클레임은 클레임이 아니므로 일반 주문으로 센다")
	void rejectedClaimIsNormalOrder() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			claim(row(1, "2026-10-01T10:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 0, "A"),
				ClaimType.CANCEL, ClaimStage.REJECTED)));
		var p = service.summary(LocalDateTime.parse("2026-10-01T00:00:00"), LocalDateTime.parse("2026-10-31T23:59:59")).period();
		assertThat(p.orderCount()).isEqualTo(1);
		assertThat(p.settlementSum()).isEqualTo(10000);
	}

	@Test
	@DisplayName("summary.period: 원가 미입력 라인은 순수익에서 빠지고 unsourcedCount·unsourcedSettlement로 따로 나온다")
	void summaryExcludesUnsourcedFromProfit() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-10-01T10:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 1000, "A"),
			row(2, "2026-10-02T10:00:00", MarketType.COUPANG, ShippingStatus.PREPARING, 8000, 0, 0, "B"),
			row(2, "2026-10-02T10:00:00", MarketType.COUPANG, ShippingStatus.PREPARING, 2000, 0, 0, "C")));
		var p = service.summary(LocalDateTime.parse("2026-10-01T00:00:00"), LocalDateTime.parse("2026-10-31T23:59:59")).period();
		assertThat(p.orderCount()).isEqualTo(2);
		assertThat(p.settlementSum()).isEqualTo(20000);
		assertThat(p.profitSum()).isEqualTo(3000);
		assertThat(p.unsourcedCount()).isEqualTo(2);
		assertThat(p.unsourcedSettlement()).isEqualTo(10000);
	}

	@Test
	@DisplayName("timeseries: 버킷의 주문 수·순수익도 취소 제외·반품 별도·원가 미입력 제외 정의를 따른다")
	void timeseriesUsesSameDefinitions() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-10-01T10:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 0, "A"),
			claim(row(2, "2026-10-01T11:00:00", MarketType.COUPANG, ShippingStatus.NEW, 5000, 0, 0, "B"),
				ClaimType.CANCEL, ClaimStage.DONE),
			claim(row(3, "2026-10-01T12:00:00", MarketType.COUPANG, ShippingStatus.PREPARING, 0, 3000, 0, "C"),
				ClaimType.RETURN, ClaimStage.DONE),
			row(4, "2026-10-01T13:00:00", MarketType.COUPANG, ShippingStatus.PREPARING, 9000, 0, 0, "D")));
		List<TimeseriesBucket> ts = service.timeseries(
			LocalDateTime.parse("2026-10-01T00:00:00"), LocalDateTime.parse("2026-10-01T23:59:59"), Unit.DAY);
		assertThat(ts).hasSize(1);
		assertThat(ts.get(0).orderCount()).isEqualTo(2);
		assertThat(ts.get(0).settlementSum()).isEqualTo(19000);
		assertThat(ts.get(0).profitSum()).isEqualTo(4000 - 3000);
	}

	@Test
	@DisplayName("breakdown VENDOR·PRODUCT: 키가 없는 라인은 UNASSIGNED(미지정)로 모여 합이 전체와 같다")
	void breakdownKeepsUnassigned() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-10-01T10:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 0, 0, "A"),
			new AggRow(2L, LocalDateTime.parse("2026-10-02T10:00:00"), MarketType.COUPANG, ShippingStatus.DELIVERED,
				ClaimType.NONE, ClaimStage.NONE, 7000, 0, 0, null, null, null, null, null)));
		LocalDateTime s = LocalDateTime.parse("2026-10-01T00:00:00"), e = LocalDateTime.parse("2026-10-31T23:59:59");
		for (Dimension dim : List.of(Dimension.VENDOR, Dimension.PRODUCT)) {
			List<BreakdownItem> b = service.breakdown(s, e, dim, 10);
			var un = b.stream().filter(x -> x.key().equals("UNASSIGNED")).findFirst().orElseThrow();
			assertThat(un.label()).isEqualTo("미지정");
			assertThat(un.orderCount()).isEqualTo(1);
			assertThat(un.settlementSum()).isEqualTo(7000);
			assertThat(b.stream().mapToInt(BreakdownItem::orderCount).sum()).isEqualTo(2);
		}
	}

	@Test
	@DisplayName("breakdown STATUS: 클레임 라인은 배송상태 대신 CLAIM(취소·반품·교환) 한 막대로 모인다")
	void breakdownStatusGroupsClaims() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-10-01T10:00:00", MarketType.COUPANG, ShippingStatus.NEW, 10000, 6000, 0, "A"),
			claim(row(2, "2026-10-02T10:00:00", MarketType.COUPANG, ShippingStatus.NEW, 5000, 0, 0, "B"),
				ClaimType.CANCEL, ClaimStage.DONE),
			claim(row(3, "2026-10-03T10:00:00", MarketType.COUPANG, ShippingStatus.PREPARING, 0, 3000, 0, "C"),
				ClaimType.RETURN, ClaimStage.DONE),
			claim(row(4, "2026-10-04T10:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 9000, 4000, 0, "D"),
				ClaimType.EXCHANGE, ClaimStage.DONE),
			row(5, "2026-10-05T10:00:00", MarketType.COUPANG, ShippingStatus.RETURNED, 0, 1000, 0, "E")));
		List<BreakdownItem> b = service.breakdown(
			LocalDateTime.parse("2026-10-01T00:00:00"), LocalDateTime.parse("2026-10-31T23:59:59"), Dimension.STATUS, 10);
		assertThat(b).extracting(BreakdownItem::key).containsExactlyInAnyOrder("NEW", "CLAIM");
		var newItem = b.stream().filter(x -> x.key().equals("NEW")).findFirst().orElseThrow();
		assertThat(newItem.orderCount()).isEqualTo(1);
		var claim = b.stream().filter(x -> x.key().equals("CLAIM")).findFirst().orElseThrow();
		assertThat(claim.label()).isEqualTo("취소·반품·교환");
		assertThat(claim.orderCount()).isEqualTo(4);
		assertThat(claim.settlementSum()).isEqualTo(9000);
		assertThat(claim.profitSum()).isEqualTo(-3000 + 5000 - 1000);
	}

	@Test
	@DisplayName("summary.current: 미발주·배송중 카운트는 클레임을 뺀 리포지토리 값을 그대로 쓴다")
	void currentUsesClaimFreeCounts() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of());
		when(repo.countByShippingStatusIn(List.of(ShippingStatus.NEW))).thenReturn(1);
		when(repo.countByShippingStatusIn(List.of(ShippingStatus.DISPATCHED, ShippingStatus.SHIPPED))).thenReturn(2);
		var c = service.summary(LocalDateTime.parse("2026-10-01T00:00:00"), LocalDateTime.parse("2026-10-31T23:59:59")).current();
		assertThat(c.newCount()).isEqualTo(1);
		assertThat(c.shippingCount()).isEqualTo(2);
	}

	private AggRow row(long orderId, String date, MarketType mk, ShippingStatus st,
		long settle, long src, long logi, String sb) {
		return new AggRow(orderId, LocalDateTime.parse(date), mk, st, ClaimType.NONE, ClaimStage.NONE,
			settle, src, logi, 1L, sb, "상품" + sb, "IHB", "IN_STOCK");
	}

	private AggRow claim(AggRow r, ClaimType type, ClaimStage stage) {
		return new AggRow(r.orderId(), r.orderDate(), r.marketType(), r.shippingStatus(), type, stage,
			r.settlementAmount(), r.sourcingAmount(), r.logisticsCost(), r.productId(), r.sbCode(),
			r.productName(), r.sourcingVendor(), r.stockStatus());
	}
}
