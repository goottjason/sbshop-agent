package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Map;

@Slf4j
@Component
public class SmartStoreStatusMapper implements MarketStatusMapper {
	@Override
	public MarketType getMarketType() {
		return MarketType.SMART_STORE;
	}

	@Override
	public ShippingStatus mapStatus(Map<String, String> marketStatuses) {
		String status = marketStatuses.get("status");
		String placeOrderStatus = marketStatuses.getOrDefault("placeOrderStatus", "");

		if (status == null) {
			return ShippingStatus.UNKNOWN;
		}

		return switch (status.toUpperCase()) {
			case "PAYED" -> "OK".equalsIgnoreCase(placeOrderStatus)
				? ShippingStatus.PREPARING
				: ShippingStatus.NEW;
			case "PAYMENT_WAITING" -> ShippingStatus.NEW;
			case "PRODUCT_PREPARE" -> ShippingStatus.PREPARING;
			case "DISPATCHED" -> ShippingStatus.DISPATCHED;
			case "DELIVERING" -> ShippingStatus.SHIPPED;
			case "DELIVERED", "PURCHASE_DECIDED" -> ShippingStatus.DELIVERED;
			case "CANCELED", "CANCELED_BY_NOPAYMENT" -> ShippingStatus.CANCELED;
			case "RETURNED" -> ShippingStatus.RETURNED;
			case "EXCHANGED" -> ShippingStatus.EXCHANGED;
			default -> {
				log.warn("알 수 없는 스마트스토어 주문 상태: {} (placeOrder={}) → UNKNOWN으로 매핑", status, placeOrderStatus);
				yield ShippingStatus.UNKNOWN;
			}
		};
	}
}
