package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * 쿠팡 주문 상태를 내부 배송 상태로 매핑하는 구현체
 */
@Slf4j
@Component
public class CoupangStatusMapper implements MarketStatusMapper {

	@Override
	public MarketType getMarketType() {
		return MarketType.COUPANG;
	}

	@Override
	public ShippingStatus mapStatus(Map<String, String> marketStatuses) {
		String status = marketStatuses.get("status");
		if (status == null) {
			return ShippingStatus.UNKNOWN;
		}
		return mapBasicStatus(status.toUpperCase());
	}

	private ShippingStatus mapBasicStatus(String s) {
		return switch (s) {
			case "ACCEPT" -> ShippingStatus.NEW;
			case "INSTRUCT" -> ShippingStatus.DISPATCHED;
			case "DELIVERING", "DEPARTURE", "NONE_TRACKING" -> ShippingStatus.SHIPPED;
			case "DELIVERED", "FINAL_DELIVERY" -> ShippingStatus.DELIVERED;
			case "CANCELED", "CANCEL_RECEIPT", "CANCEL_DONE" -> ShippingStatus.CANCELED;
			case "RETURN_RECEIPT", "RETURN_DONE" -> ShippingStatus.RETURNED;
			case "EXCHANGE_RECEIPT", "EXCHANGE_DONE" -> ShippingStatus.EXCHANGED;
			default -> {
				log.warn("알 수 없는 쿠팡 주문 상태: {} → UNKNOWN으로 매핑", s);
				yield ShippingStatus.UNKNOWN;
			}
		};
	}
}
