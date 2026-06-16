package com.sbshop.agent.core.application.order.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class OrderSearchCondition {
	private List<MarketType> marketTypes;
	private List<ShippingStatus> shippingStatuses;
	private String keyword;
	private LocalDateTime startDate;
	private LocalDateTime endDate;
}
