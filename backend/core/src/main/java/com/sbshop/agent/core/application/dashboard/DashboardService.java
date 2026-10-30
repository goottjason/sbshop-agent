package com.sbshop.agent.core.application.dashboard;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AttentionResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.BreakdownItem;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Dimension;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.SummaryResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.TimeseriesBucket;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

	private static final List<CustomsStatus> CUSTOMS_ISSUES = List.of(
		CustomsStatus.PENDING, CustomsStatus.INVALID_PCCC,
		CustomsStatus.INVALID_PHONE, CustomsStatus.INVALID_ZIPCODE);

	private static final String UNASSIGNED = "UNASSIGNED";
	private static final String CLAIM = "CLAIM";

	private final DashboardRepository repo;

	public SummaryResponse summary(LocalDateTime start, LocalDateTime end) {
		Totals t = Totals.of(repo.findRowsBetween(start, end));
		var current = new SummaryResponse.Current(
			repo.countByShippingStatusIn(List.of(ShippingStatus.NEW)),
			repo.countByShippingStatusIn(List.of(ShippingStatus.DISPATCHED, ShippingStatus.SHIPPED)),
			repo.countCustomsIssue(CUSTOMS_ISSUES));
		return new SummaryResponse(new SummaryResponse.Period(t.orderCount, t.settlement, t.profit,
			t.returnCount, t.returnProfit, t.unsourcedCount, t.unsourcedSettlement), current);
	}

	public List<TimeseriesBucket> timeseries(LocalDateTime start, LocalDateTime end, Unit unit) {
		Map<LocalDate, List<AggRow>> byBucket = new TreeMap<>();
		for (LocalDate b : DashboardBucketing.bucketRange(start, end, unit))
			byBucket.put(b, new ArrayList<>());
		for (AggRow r : repo.findRowsBetween(start, end))
			byBucket.computeIfAbsent(DashboardBucketing.bucketKey(r.orderDate(), unit), k -> new ArrayList<>()).add(r);
		List<TimeseriesBucket> out = new ArrayList<>();
		byBucket.forEach((b, rows) -> {
			Totals t = Totals.of(rows);
			out.add(new TimeseriesBucket(b.toString(), t.orderCount, t.settlement, t.profit));
		});
		return out;
	}

	public List<BreakdownItem> breakdown(LocalDateTime start, LocalDateTime end, Dimension dim, int limit) {
		Map<String, List<AggRow>> byKey = new LinkedHashMap<>();
		Map<String, String> labels = new LinkedHashMap<>();
		for (AggRow r : repo.findRowsBetween(start, end)) {
			String key = keyOf(r, dim);
			byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
			labels.putIfAbsent(key, labelOf(r, dim, key));
		}
		List<BreakdownItem> items = byKey.entrySet().stream()
			.map(e -> {
				Totals t = Totals.of(e.getValue());
				int count = CLAIM.equals(e.getKey())
					? (int)e.getValue().stream().map(AggRow::orderId).distinct().count()
					: t.orderCount;
				return new BreakdownItem(e.getKey(), labels.get(e.getKey()), count, t.settlement, t.profit);
			})
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
			repo.countActiveClaimsInStages(List.of(ClaimStage.REQUESTED, ClaimStage.IN_PROGRESS)));
	}

	private static final class Totals {
		int orderCount;
		int returnCount;
		int unsourcedCount;
		long settlement;
		long profit;
		long returnProfit;
		long unsourcedSettlement;

		static Totals of(List<AggRow> rows) {
			Totals t = new Totals();
			Set<Long> normalOrders = new HashSet<>();
			Set<Long> returnOrders = new HashSet<>();
			for (AggRow r : rows) {
				if (r.canceled())
					continue;
				(r.returned() ? returnOrders : normalOrders).add(r.orderId());
				t.settlement += r.settlementAmount();
				if (!r.sourced()) {
					t.unsourcedCount++;
					t.unsourcedSettlement += r.settlementAmount();
					continue;
				}
				t.profit += r.profit();
				if (r.returned())
					t.returnProfit += r.profit();
			}
			returnOrders.removeAll(normalOrders);
			t.orderCount = normalOrders.size();
			t.returnCount = returnOrders.size();
			return t;
		}
	}

	private String keyOf(AggRow r, Dimension dim) {
		String key = switch (dim) {
			case MARKET -> r.marketType() == null ? null : r.marketType().name();
			case STATUS -> r.claimLine() ? CLAIM : r.shippingStatus() == null ? null : r.shippingStatus().name();
			case PRODUCT -> r.sbCode();
			case VENDOR -> r.sourcingVendor();
		};
		return key == null || key.isBlank() ? UNASSIGNED : key;
	}

	private String labelOf(AggRow r, Dimension dim, String key) {
		if (UNASSIGNED.equals(key))
			return "미지정";
		if (CLAIM.equals(key))
			return "취소·반품·교환";
		return switch (dim) {
			case MARKET -> {
				try {
					yield MarketType.valueOf(key).getLabel();
				} catch (Exception e) {
					yield key;
				}
			}
			case PRODUCT -> r.productName() != null ? r.productName() : key;
			default -> key;
		};
	}
}
