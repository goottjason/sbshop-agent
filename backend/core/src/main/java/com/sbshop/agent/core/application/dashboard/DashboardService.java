package com.sbshop.agent.core.application.dashboard;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AttentionResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.BreakdownItem;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Dimension;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.SummaryResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.TimeseriesBucket;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

	private static final List<CustomsStatus> CUSTOMS_ISSUES = List.of(
		CustomsStatus.PENDING, CustomsStatus.INVALID_PCCC,
		CustomsStatus.INVALID_PHONE, CustomsStatus.INVALID_ZIPCODE);

	private final DashboardRepository repo;

	public SummaryResponse summary(LocalDateTime start, LocalDateTime end) {
		List<AggRow> rows = repo.findRowsBetween(start, end);
		int orderCount = (int) rows.stream().map(AggRow::orderId).distinct().count();
		long settlement = rows.stream().mapToLong(AggRow::settlementAmount).sum();
		long profit = rows.stream().mapToLong(AggRow::profit).sum();
		var current = new SummaryResponse.Current(
			repo.countByShippingStatusIn(List.of(ShippingStatus.NEW)),
			repo.countByShippingStatusIn(List.of(ShippingStatus.DISPATCHED, ShippingStatus.SHIPPED)),
			repo.countCustomsIssue(CUSTOMS_ISSUES));
		return new SummaryResponse(new SummaryResponse.Period(orderCount, settlement, profit), current);
	}

	public List<TimeseriesBucket> timeseries(LocalDateTime start, LocalDateTime end, Unit unit) {
		List<AggRow> rows = repo.findRowsBetween(start, end);
		// 버킷별: distinct 주문 집합 + 금액 합.
		// A1 리뷰 Important 대응: 축(x)은 bucketRange(빈 구간 0채움)와 실제 주문의 KST 버킷키의
		// 합집합으로 구성한다. naive 경계(bucketRange)와 KST 주문키(bucketKey)의 9h 스큐로 마지막 날
		// UTC 꼬리 주문이 다음 KST 버킷으로 가더라도 축에 포함되어 절대 누락되지 않는다.
		Map<LocalDate, Set<Long>> orders = new java.util.TreeMap<>();  // 버킷키 오름차순 정렬
		Map<LocalDate, long[]> sums = new java.util.HashMap<>();       // [settlement, profit]
		java.util.function.Consumer<LocalDate> ensure = b -> {
			orders.computeIfAbsent(b, k -> new java.util.HashSet<>());
			sums.computeIfAbsent(b, k -> new long[2]);
		};
		for (LocalDate b : DashboardBucketing.bucketRange(start, end, unit)) ensure.accept(b);
		for (AggRow r : rows) {
			LocalDate b = DashboardBucketing.bucketKey(r.orderDate(), unit);
			ensure.accept(b);                     // 축에 없던 KST 꼬리 버킷도 편입(누락 방지)
			orders.get(b).add(r.orderId());
			long[] s = sums.get(b);
			s[0] += r.settlementAmount();
			s[1] += r.profit();
		}
		List<TimeseriesBucket> out = new ArrayList<>();
		for (LocalDate b : orders.keySet()) {     // TreeMap → 오름차순
			long[] s = sums.get(b);
			out.add(new TimeseriesBucket(b.toString(), orders.get(b).size(), s[0], s[1]));
		}
		return out;
	}

	public List<BreakdownItem> breakdown(LocalDateTime start, LocalDateTime end, Dimension dim, int limit) {
		List<AggRow> rows = repo.findRowsBetween(start, end);
		Map<String, Set<Long>> ordersByKey = new LinkedHashMap<>();
		Map<String, long[]> sumsByKey = new LinkedHashMap<>();
		Map<String, String> labels = new LinkedHashMap<>();
		for (AggRow r : rows) {
			String key = keyOf(r, dim);
			if (key == null) continue;
			ordersByKey.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(r.orderId());
			sumsByKey.computeIfAbsent(key, k -> new long[2]);
			sumsByKey.get(key)[0] += r.settlementAmount();
			sumsByKey.get(key)[1] += r.profit();
			labels.putIfAbsent(key, labelOf(r, dim, key));
		}
		List<BreakdownItem> items = ordersByKey.keySet().stream()
			.map(k -> new BreakdownItem(k, labels.get(k), ordersByKey.get(k).size(),
				sumsByKey.get(k)[0], sumsByKey.get(k)[1]))
			.sorted((a, b) -> Integer.compare(b.orderCount(), a.orderCount()))
			.collect(Collectors.toList());
		return limit > 0 && items.size() > limit ? items.subList(0, limit) : items;
	}

	public AttentionResponse attention(LocalDateTime now) {
		LocalDateTime newBefore = now.minusDays(1);
		LocalDateTime preparingBefore = now.minusDays(3);
		return new AttentionResponse(
			repo.countCustomsIssue(CUSTOMS_ISSUES),
			repo.countOutOfStock(),
			repo.countDelayed(newBefore, preparingBefore),
			repo.countByShippingStatusIn(List.of(ShippingStatus.CANCELED, ShippingStatus.RETURNED)));
	}

	private String keyOf(AggRow r, Dimension dim) {
		return switch (dim) {
			case MARKET -> r.marketType() == null ? null : r.marketType().name();
			case STATUS -> r.shippingStatus() == null ? null : r.shippingStatus().name();
			case PRODUCT -> r.sbCode();
			case VENDOR -> r.sourcingVendor();
		};
	}

	private String labelOf(AggRow r, Dimension dim, String key) {
		return switch (dim) {
			case MARKET -> { try { yield MarketType.valueOf(key).getLabel(); } catch (Exception e) { yield key; } }
			case PRODUCT -> r.productName() != null ? r.productName() : key;
			default -> key;   // STATUS·VENDOR는 프론트에서 라벨링
		};
	}
}
