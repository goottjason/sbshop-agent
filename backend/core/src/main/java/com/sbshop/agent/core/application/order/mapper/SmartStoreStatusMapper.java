package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
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
			case "DELIVERED" -> ShippingStatus.DELIVERED;
			case "PURCHASE_DECIDED" -> ShippingStatus.CONFIRMED;
			case "CANCELED", "CANCELED_BY_NOPAYMENT", "RETURNED", "EXCHANGED" -> ShippingStatus.UNKNOWN;
			default -> {
				log.warn("알 수 없는 스마트스토어 주문 상태: {} (placeOrder={}) → UNKNOWN으로 매핑", status, placeOrderStatus);
				yield ShippingStatus.UNKNOWN;
			}
		};
	}

	public ClaimData mapClaim(String productOrderStatus, String claimType, String claimStatus) {
		if ("CANCELED_BY_NOPAYMENT".equalsIgnoreCase(trim(productOrderStatus))) {
			return ClaimData.builder()
				.claimType(ClaimType.CANCEL)
				.claimStage(ClaimStage.DONE)
				.claimRawCode(trim(productOrderStatus))
				.build();
		}

		ClaimType type = claimTypeOf(trim(claimType));
		if (type == ClaimType.NONE) {
			return ClaimData.builder().build();
		}

		String raw = trim(claimStatus) != null ? trim(claimStatus) : trim(claimType);
		return ClaimData.builder()
			.claimType(type)
			.claimStage(claimStageOf(trim(claimStatus)))
			.claimRawCode(raw)
			.build();
	}

	private ClaimType claimTypeOf(String claimType) {
		if (claimType == null) {
			return ClaimType.NONE;
		}
		return switch (claimType.toUpperCase()) {
			case "CANCEL", "ADMIN_CANCEL" -> ClaimType.CANCEL;
			case "RETURN" -> ClaimType.RETURN;
			case "EXCHANGE" -> ClaimType.EXCHANGE;
			default -> ClaimType.NONE;
		};
	}

	private ClaimStage claimStageOf(String claimStatus) {
		if (claimStatus == null) {
			return ClaimStage.NONE;
		}
		String s = claimStatus.toUpperCase();
		if (s.equals("CANCELING") || s.equals("COLLECTING")
			|| s.equals("COLLECT_DONE") || s.equals("EXCHANGE_REDELIVERING")) {
			return ClaimStage.IN_PROGRESS;
		}
		if (s.endsWith("_REQUEST")) {
			return ClaimStage.REQUESTED;
		}
		if (s.endsWith("_DONE")) {
			return ClaimStage.DONE;
		}
		if (s.endsWith("_REJECT")) {
			return ClaimStage.REJECTED;
		}
		return ClaimStage.NONE;
	}

	private String trim(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		return s.trim();
	}
}
