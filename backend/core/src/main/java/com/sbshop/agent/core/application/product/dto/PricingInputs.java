package com.sbshop.agent.core.application.product.dto;

import java.math.BigDecimal;

/**
 * D-094: 마켓별 판매가를 산정하기 위한 원재료.
 * 마켓별 실수수료를 divisor에 넣어 각 마켓마다 다른 판매가를 계산하려면, 단일 완성가(price) 대신
 * 계산 재료(실매입가·묶음수·마진·쿠폰·최소마진)를 넘겨야 한다(쿠폰율·최소마진은 상품에 저장되지 않으므로).
 *
 * @param buyPrice       크롤한 실매입가(쿠폰 반영 전 단가)
 * @param bundleQty      묶음 수량
 * @param marginRate     목표 마진율(%)
 * @param couponRate     매입 쿠폰 할인율(%) — 실매입가를 낮춘다
 * @param minMarginPrice 최소 마진(원) — 이 미만이면 보정
 */
public record PricingInputs(
	BigDecimal buyPrice, int bundleQty, BigDecimal marginRate, BigDecimal couponRate,
	BigDecimal minMarginPrice) {
}
