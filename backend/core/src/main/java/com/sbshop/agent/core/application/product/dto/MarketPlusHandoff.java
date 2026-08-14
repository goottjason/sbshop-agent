package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;

/**
 * G마켓·옥션 전송을 사람에게 넘기기 위한 정보.
 *
 * @param marketType        GMARKET 또는 AUCTION
 * @param cafe24ProductCode 마켓플러스 미판매 목록에서 이 상품을 찾는 유일한 검색키.
 *                          자체상품코드(sbCode)로는 검색되지 않는다(스파이크 실측).
 */
public record MarketPlusHandoff(MarketType marketType, String cafe24ProductCode) {
}
