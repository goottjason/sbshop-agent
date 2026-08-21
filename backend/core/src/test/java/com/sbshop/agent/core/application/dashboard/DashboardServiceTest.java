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
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
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

	private AggRow row(long orderId, String date, MarketType mk, ShippingStatus st,
		long settle, long src, long logi, String sb) {
		return new AggRow(orderId, LocalDateTime.parse(date), mk, st, settle, src, logi,
			1L, sb, "상품" + sb, "IHB", "IN_STOCK");
	}

	@Test
	@DisplayName("summary.period: 주문수는 distinct order, 금액은 lineItem 합산")
	void summaryAggregates() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 1000, "A"),
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 5000, 3000, 500, "B"),
			row(2, "2026-07-02T01:00:00", MarketType.SMART_STORE, ShippingStatus.SHIPPED, 20000, 12000, 2000, "C")));
		var s = service.summary(LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-31T23:59:59"));
		assertThat(s.period().orderCount()).isEqualTo(2);          // distinct order 1,2
		assertThat(s.period().settlementSum()).isEqualTo(35000);   // 10000+5000+20000
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
		assertThat(ts.get(1).orderCount()).isEqualTo(0);   // 07-02 빈 구간
		assertThat(ts.get(2).orderCount()).isEqualTo(0);
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
	@DisplayName("attention: 리포지토리 카운트를 그대로 조립")
	void attentionAssembles() {
		when(repo.countCustomsIssue(any())).thenReturn(3);
		when(repo.countOutOfStock()).thenReturn(2);
		when(repo.countDelayed(any(), any())).thenReturn(5);
		when(repo.countByShippingStatusIn(List.of(ShippingStatus.CANCELED, ShippingStatus.RETURNED))).thenReturn(4);
		var a = service.attention(LocalDateTime.parse("2026-07-25T10:00:00"));
		assertThat(a.customsIssue()).isEqualTo(3);
		assertThat(a.outOfStock()).isEqualTo(2);
		assertThat(a.delayed()).isEqualTo(5);
		assertThat(a.returnCancel()).isEqualTo(4);
	}
}
