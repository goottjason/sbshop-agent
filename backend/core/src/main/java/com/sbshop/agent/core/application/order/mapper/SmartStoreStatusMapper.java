package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * 스마트스토어 주문 상태를 내부 배송 상태로 매핑하는 구현체
 */
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
			case "PAYED" -> {
				if ("OK".equalsIgnoreCase(placeOrderStatus)) {
					yield ShippingStatus.PREPARING;
				}
				if ("CANCEL".equalsIgnoreCase(placeOrderStatus)) {
					yield ShippingStatus.CANCELED;
				}
				yield ShippingStatus.NEW;
			}
			case "PAYMENT_WAITING" -> ShippingStatus.NEW;
			case "PRODUCT_PREPARE" -> ShippingStatus.PREPARING;
			case "DISPATCHED", "DELIVERING" -> ShippingStatus.SHIPPED;
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
