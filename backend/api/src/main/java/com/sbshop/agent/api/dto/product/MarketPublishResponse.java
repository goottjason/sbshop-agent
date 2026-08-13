package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import java.util.Map;

/**
 * 배지 클릭 등록의 응답. 프론트는 이 값만으로 목록 재조회 없이 배지를 갱신한다.
 *
 * @param market      MarketType.name()
 * @param status      "SYNCED" 또는 "PENDING"({@link MarketBadgeState}와 같은 어휘)
 * @param url         마켓 상품페이지 URL(아직 링크 식별자가 없으면 null)
 * @param identifiers 마켓이 돌려준 식별자 원본
 */
public record MarketPublishResponse(String market, String status, String url, Map<String, String> identifiers) {

	public static MarketPublishResponse from(MarketPublishOutcome outcome, String url) {
		return new MarketPublishResponse(
			outcome.marketType().name(),
			outcome.synced() ? MarketBadgeState.SYNCED : MarketBadgeState.PENDING,
			(url == null || url.isBlank()) ? null : url,
			outcome.identifiers());
	}
}
