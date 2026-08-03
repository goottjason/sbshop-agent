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
			// D-117: PAYED(결제완료=발송대기)는 살아있는 주문이다. placeOrderStatus는 주문 취소 여부가 아니라
			// 발주확인 여부(OK 확인완료 / NOT_YET 확인전 / CANCEL 확인해제)를 나타내는 별개의 축이므로,
			// CANCEL(발주확인 해제)을 주문취소로 매핑하면 안 된다 — 발주확인만 되돌아간 발송대기 주문이다.
			// 실제 취소는 아래 productOrderStatus=CANCELED/CANCELED_BY_NOPAYMENT 분기가 담당한다.
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
