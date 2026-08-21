package com.sbshop.agent.core.application.dashboard.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDateTime;

public final class DashboardDtos {
	private DashboardDtos() {}

	public record AggRow(
		Long orderId,
		LocalDateTime orderDate,
		MarketType marketType,
		ShippingStatus shippingStatus,
		long settlementAmount,
		long sourcingAmount,
		long logisticsCost,
		Long productId,
		String sbCode,
		String productName,
		String sourcingVendor,
		String stockStatus) {
		public long profit() {
			return settlementAmount - sourcingAmount - logisticsCost;
		}
	}

	public record SummaryResponse(Period period, Current current) {
		public record Period(int orderCount, long settlementSum, long profitSum) {
		}
		public record Current(int newCount, int shippingCount, int customsIssueCount) {
		}
	}

	public record TimeseriesBucket(String bucketStart, int orderCount, long settlementSum, long profitSum) {
	}

	public record BreakdownItem(String key, String label, int orderCount, long settlementSum, long profitSum) {
	}

	public record AttentionResponse(int customsIssue, int outOfStock, int delayed, int returnCancel) {
	}

	public enum Unit {
		DAY, WEEK, MONTH
	}

	public enum Dimension {
		MARKET, STATUS, PRODUCT, VENDOR
	}
}
