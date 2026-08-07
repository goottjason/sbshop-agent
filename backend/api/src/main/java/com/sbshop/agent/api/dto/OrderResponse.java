package com.sbshop.agent.api.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.vo.CustomsData;

import lombok.Builder;
import lombok.Getter;

/**
 * Order 쓰기 응답 DTO (SP-5 / F-ORD-7·16·24·28).
 *
 * <p>도메인 엔티티를 API 응답으로 직접 노출하지 않기 위한 경계 DTO. 단, 프론트 계약을 보존하기 위해
 * 현재 {@link Order} 엔티티가 직렬화되는 JSON 형태(파생 getter 포함)를 그대로 미러한다.
 * 필드 이름·구조·값을 엔티티 getter와 1:1로 매핑하므로 직렬화 결과가 엔티티와 동일하다
 * (검증: {@code OrderResponseContractTest}).
 */
@Getter
@Builder
public class OrderResponse {

	// BaseEntity 유래 필드
	private final Long id;
	private final RecordStatus status;
	private final LocalDateTime createdAt;
	private final LocalDateTime updatedAt;

	// Order 필드
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

	// 엔티티의 파생 getter(getMarketSpecificDataMap/getCafe24OrderId)도 현재 JSON에 포함되므로 미러
	private final Map<String, String> marketSpecificDataMap;
	private final String cafe24OrderId;

	/** 엔티티를 응답 DTO로 매핑. 현재 직렬화 형태를 그대로 보존한다. */
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
			.marketSpecificDataMap(order.getMarketSpecificDataMap())
			.cafe24OrderId(order.getCafe24OrderId())
			.build();
	}
}
