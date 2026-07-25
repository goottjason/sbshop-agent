package com.sbshop.agent.core.application.order.dto;

import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class OrderSearchCondition {
	private List<MarketType> marketTypes;
	private List<ShippingStatus> shippingStatuses;
	// 구매상태 필터(미구매/구매완료/입고대기). 미지정 시 무시.
	private List<PurchaseStatus> purchaseStatuses;
	// 통관상태 필터(대시보드 '통관 오류/대기' 집계용). 미지정 시 무시.
	private List<CustomsStatus> customsStatuses;
	// 재고상태 필터(대시보드 '재고부족' 드릴다운). Product.stockStatus enum name. 미지정 시 무시.
	private List<String> stockStatuses;
	// 소싱처 필터(대시보드 소싱처별 드릴다운). OrderLineItem.sourcingData.sourcingVendor. 미지정 시 무시.
	private List<String> vendors;
	private String keyword;
	private LocalDateTime startDate;
	private LocalDateTime endDate;
}
