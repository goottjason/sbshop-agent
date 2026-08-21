package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.product.dto.MarketPlusHandoff;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketPlusHandoffService {
	private final MarketRegistrationRepository marketRegistrationRepository;

	public MarketPlusHandoff resolve(Long productId, MarketType marketType) {
		if (marketType != MarketType.GMARKET && marketType != MarketType.AUCTION) {
			throw new IllegalArgumentException("마켓플러스 경유 대상이 아닙니다: " + marketType);
		}
		MarketRegistration cafe24 = marketRegistrationRepository
			.findByProductIdAndMarketType(productId, MarketType.CAFE24)
			.orElseThrow(() -> new IllegalStateException(
				"카페24 등록이 먼저 필요합니다 — G마켓·옥션은 마켓플러스를 경유합니다"));

		String productCode = cafe24.identifier("product_code");
		if (productCode == null || productCode.isBlank()) {
			throw new IllegalStateException(
				"카페24 상품코드가 없어 마켓플러스에서 상품을 찾을 수 없습니다 — 카페24 재등록이 필요합니다");
		}
		return new MarketPlusHandoff(marketType, productCode);
	}
}
