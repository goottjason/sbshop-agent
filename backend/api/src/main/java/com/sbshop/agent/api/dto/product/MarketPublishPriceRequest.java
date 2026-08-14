package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import java.math.BigDecimal;

/**
 * 결함 B: 마켓 등록 API가 받는 선택적 가격 파라미터.
 *
 * <p>등록가 산정(마진율·쿠폰율·최소마진)은 원래 정기 재가격 배치가 나중에 바로잡아 줄 것으로
 * 가정했으나, 그 배치는 D-093 사용자 결정으로 비활성이다. 그래서 프론트 다이얼로그가 이 값을
 * 직접 받아 등록 시점에 반영한다. 요청 바디가 없거나 필드가 비어 있으면 종전 동작(오버라이드 없음)과
 * 같다 — 기존 호출부를 깨지 않는다.
 */
public record MarketPublishPriceRequest(
	BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {

	public MarketSalePriceOverrides toOverrides() {
		return new MarketSalePriceOverrides(marginRate, couponRate, minMarginPrice);
	}
}
