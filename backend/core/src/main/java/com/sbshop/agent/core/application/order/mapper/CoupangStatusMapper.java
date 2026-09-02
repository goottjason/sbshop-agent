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
			case "INSTRUCT" -> ShippingStatus.PREPARING;
			case "DEPARTURE" -> ShippingStatus.DISPATCHED;
			case "DELIVERING", "NONE_TRACKING" -> ShippingStatus.SHIPPED;
			case "DELIVERED", "FINAL_DELIVERY" -> ShippingStatus.DELIVERED;
			default -> {
				log.warn("알 수 없는 쿠팡 주문 상태: {} → UNKNOWN으로 매핑", s);
				yield ShippingStatus.UNKNOWN;
			}
		};
	}

	public ClaimData mapClaim(String receiptType, String receiptStatus) {
		ClaimType type = returnClaimTypeOf(receiptType);
		if (type == ClaimType.NONE) {
			return ClaimData.builder().build();
		}
		return ClaimData.builder()
			.claimType(type)
			.claimStage(returnClaimStageOf(receiptStatus))
			.claimRawCode(receiptStatus)
			.build();
	}

	public ClaimData mapExchangeClaim(String exchangeStatus) {
		if (exchangeStatus == null) {
			return ClaimData.builder().build();
		}
		ClaimStage stage = switch (exchangeStatus.trim().toUpperCase()) {
			case "RECEIPT" -> ClaimStage.REQUESTED;
			case "PROGRESS" -> ClaimStage.IN_PROGRESS;
			case "SUCCESS" -> ClaimStage.DONE;
			case "REJECT", "CANCEL" -> ClaimStage.REJECTED;
			default -> ClaimStage.NONE;
		};
		if (stage == ClaimStage.NONE) {
			return ClaimData.builder().build();
		}
		return ClaimData.builder()
			.claimType(ClaimType.EXCHANGE)
			.claimStage(stage)
			.claimRawCode(exchangeStatus)
			.build();
	}

	private ClaimType returnClaimTypeOf(String receiptType) {
		if (receiptType == null) {
			return ClaimType.NONE;
		}
		return switch (receiptType.trim().toUpperCase()) {
			case "RETURN" -> ClaimType.RETURN;
			case "CANCEL" -> ClaimType.CANCEL;
			default -> ClaimType.NONE;
		};
	}

	private ClaimStage returnClaimStageOf(String receiptStatus) {
		if (receiptStatus == null) {
			return ClaimStage.IN_PROGRESS;
		}
		return switch (receiptStatus.trim().toUpperCase()) {
			case "RETURNS_UNCHECKED" -> ClaimStage.REQUESTED;
			case "RETURNS_COMPLETED" -> ClaimStage.DONE;
			default -> ClaimStage.IN_PROGRESS;
		};
	}
}
