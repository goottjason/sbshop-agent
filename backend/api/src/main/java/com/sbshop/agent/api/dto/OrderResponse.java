package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.OrderProbeStatus;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderResponse {

	private final Long id;
	private final RecordStatus status;
	private final LocalDateTime createdAt;
	private final LocalDateTime updatedAt;

	private final MarketType marketType;
	private final String marketOrderNo;
	private final LocalDateTime orderDate;
	private final String recipientName;
	private final String recipientPhone;
	private final String zipcode;
	private final String address;
	private final String message;
	private final CustomsData customsData;
	private final String ordererName;
	private final String ordererPhone;
	private final String marketSpecificData;
	private final OrderProbeStatus lastProbeStatus;
	private final LocalDateTime lastProbeAt;

	private final Map<String, String> marketSpecificDataMap;
	private final String cafe24OrderId;

	public static OrderResponse from(Order order) {
		return OrderResponse.builder()
			.id(order.getId())
			.status(order.getStatus())
			.createdAt(order.getCreatedAt())
			.updatedAt(order.getUpdatedAt())
			.marketType(order.getMarketType())
			.marketOrderNo(order.getMarketOrderNo())
			.orderDate(order.getOrderDate())
			.recipientName(order.getRecipientName())
			.recipientPhone(order.getRecipientPhone())
			.zipcode(order.getZipcode())
			.address(order.getAddress())
			.message(order.getMessage())
			.customsData(order.getCustomsData())
			.ordererName(order.getOrdererName())
			.ordererPhone(order.getOrdererPhone())
			.marketSpecificData(order.getMarketSpecificData())
			.lastProbeStatus(order.getLastProbeStatus())
			.lastProbeAt(order.getLastProbeAt())
			.marketSpecificDataMap(order.getMarketSpecificDataMap())
			.cafe24OrderId(order.getCafe24OrderId())
			.build();
	}
}
