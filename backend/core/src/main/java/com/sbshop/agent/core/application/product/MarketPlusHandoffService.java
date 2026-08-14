package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.product.dto.MarketPlusHandoff;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * G마켓·옥션 등록을 사람에게 넘긴다.
 *
 * <p>두 마켓에는 상품등록 API가 없다. 유일한 경로는 Cafe24에 등록된 상품을 마켓플러스
 * 미판매 목록에서 골라 '일괄 보내기'로 내보내는 것인데, 그 과정에 <b>상품마다 마켓 카테고리
 * 4단계를 사람이 골라야 하고</b> 전송 팝업에 reCAPTCHA가 걸린다. 그래서 자동 전송 대신
 * "어느 상품코드로 찾으면 되는지"까지만 서버가 책임지고 나머지는 사람이 한다.
 *
 * <p>마켓플러스 목록은 Cafe24 {@code product_code} <b>완전일치</b>로만 검색된다 —
 * 자체상품코드(sbCode)는 그 화면에 노출조차 되지 않는다(스파이크 실측).
 */
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
