package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 11번가 주문 상태를 내부 배송 상태로 매핑하는 구현체
 */
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

		// API 소스에 따라 상태 매핑
		if (source != null) {
			return mapBySource(source);
		}

		return ShippingStatus.PREPARING;
	}

	/**
	 * API 호출 소스에 따른 상태 매핑
	 */
	private ShippingStatus mapBySource(String source) {
		return switch (source.toLowerCase()) {
			case "complete" -> ShippingStatus.NEW; // 결제완료
			case "packaging" -> ShippingStatus.PREPARING; // 배송준비중
			case "shipping" -> ShippingStatus.SHIPPED; // 배송중
			case "dlvcompleted" -> ShippingStatus.DELIVERED; // 배송완료
			default -> {
				log.warn("알 수 없는 11번가 주문 상태 소스: {} → PREPARING으로 매핑", source);
				yield ShippingStatus.PREPARING;
			}
		};
	}
}
