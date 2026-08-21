package com.sbshop.agent.core.application.dashboard.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDateTime;

/** 대시보드 집계 DTO 모음. */
public final class DashboardDtos {
	private DashboardDtos() {}

	/** 리포지토리가 기간 내 주문×라인아이템×상품을 평면 행으로 투영한 것. 서비스가 Java 집계. */
	public record AggRow(
		Long orderId,
		LocalDateTime orderDate, // zone 없는 UTC 벽시계값(KST 변환 대상)
		MarketType marketType,
		ShippingStatus shippingStatus,
		long settlementAmount, // null→0 로 매핑됨
		long sourcingAmount,
		long logisticsCost,
		Long productId,
		String sbCode,
		String productName,
		String sourcingVendor,
		String stockStatus) { // Product.stockStatus enum name (IN_STOCK/OUT_OF_STOCK) or null
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
