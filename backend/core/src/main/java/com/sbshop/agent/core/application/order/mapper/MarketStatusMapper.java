package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;

/**
 * 마켓별 주문 상태를 내부 배송 상태로 매핑하는 인터페이스
 * 각 마켓 어댑터가 구현하여 상태 매핑 로직을 캡슐화
 */
public interface MarketStatusMapper {

	/**
	 * 마켓 타입 식별
	 */
	MarketType getMarketType();

	/**
	 * 마켓 상태를 내부 배송 상태로 매핑
	 */
	ShippingStatus mapStatus(Map<String, String> marketStatuses);
}
