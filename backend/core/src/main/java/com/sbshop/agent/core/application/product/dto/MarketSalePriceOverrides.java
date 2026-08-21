package com.sbshop.agent.core.application.product.dto;

import java.math.BigDecimal;

/**
 * 신규 등록 시 등록가 산정에 반영할 배치 파라미터 오버라이드.
 *
 * <p>{@link com.sbshop.agent.core.application.product.MarketSalePriceResolver#resolveForProduct}는
 * 원가·마진율만으로 산정해 쿠폰율·최소마진을 반영하지 못했다(둘 다 상품에 저장되지 않는 배치 실행
 * 파라미터라서). 그 결과 등록가가 동기화 배치가 계산하는 목표가보다 눈에 띄게 높게 나갔다.
 * 이 레코드는 API 호출자가 그 값을 직접 넘길 수 있게 한다 — 전부 nullable이며, null이면
 * 종전과 같이 그 항목을 계산에 반영하지 않는다.
 */
public record MarketSalePriceOverrides(
	BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {

	public static final MarketSalePriceOverrides EMPTY = new MarketSalePriceOverrides(null, null, null);
}
