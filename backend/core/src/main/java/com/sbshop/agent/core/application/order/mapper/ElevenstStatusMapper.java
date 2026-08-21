package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ElevenstStatusMapper implements MarketStatusMapper {
	@Override
	public MarketType getMarketType() {
		return MarketType.ELEVEN_STREET;
	}

	@Override
	public ShippingStatus mapStatus(Map<String, String> marketStatuses) {
		String source = marketStatuses.get("source");

		if (source != null) {
			return mapBySource(source);
		}

		return ShippingStatus.UNKNOWN;
	}

	public ShippingStatus mapClaimStatus(String ordPrdStat, String ordPrdStatNm) {
		if (ordPrdStatNm == null || ordPrdStatNm.isBlank()) {
			return null;
		}
		if (ordPrdStatNm.contains("취소")) {
			return ShippingStatus.CANCELED;
		}
		if (ordPrdStatNm.contains("반품")) {
			return ShippingStatus.RETURNED;
		}
		if (ordPrdStatNm.contains("교환")) {
			return ShippingStatus.EXCHANGED;
		}
		return null;
	}

	public ShippingStatus mapProductOrderStatus(String ordPrdStatNm) {
		if (ordPrdStatNm == null || ordPrdStatNm.isBlank()) {
			return ShippingStatus.UNKNOWN;
		}
		String name = ordPrdStatNm.trim();

		if (name.contains("취소")) {
			return ShippingStatus.CANCELED;
		}
		if (name.contains("반품")) {
			return ShippingStatus.RETURNED;
		}
		if (name.contains("교환")) {
			return ShippingStatus.EXCHANGED;
		}

		if (name.contains("구매확정") || name.contains("배송완료")) {
			return ShippingStatus.DELIVERED;
		}
		if (name.contains("배송준비중")) {
			return ShippingStatus.PREPARING;
		}
		if (name.contains("발송완료") || name.contains("배송중")) {
			return ShippingStatus.SHIPPED;
		}
		if (name.contains("결제완료")) {
			return ShippingStatus.NEW;
		}

		log.warn("11번가 상품주문 상태명 미매핑: '{}' → UNKNOWN (상태를 덮지 않는다)", ordPrdStatNm);
		return ShippingStatus.UNKNOWN;
	}

	private ShippingStatus mapBySource(String source) {
		return switch (source.toLowerCase()) {
			case "complete" -> ShippingStatus.NEW;
			case "packaging" -> ShippingStatus.PREPARING;
			case "shipping" -> ShippingStatus.SHIPPED;
			case "dlvcompleted" -> ShippingStatus.DELIVERED;
			default -> {
				log.warn("알 수 없는 11번가 주문 상태 소스: {} → UNKNOWN으로 매핑", source);
				yield ShippingStatus.UNKNOWN;
			}
		};
	}
}
