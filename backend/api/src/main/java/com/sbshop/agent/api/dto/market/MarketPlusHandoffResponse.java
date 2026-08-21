package com.sbshop.agent.api.dto.market;

import com.sbshop.agent.core.application.product.dto.MarketPlusHandoff;

public record MarketPlusHandoffResponse(String market, String cafe24ProductCode,
	String marketplusUrl, String guide) {

	private static final String MARKETPLUS_NO_SALE_URL = "https://mp.cafe24.com/mp/product/front/noSaleAll";

	public static MarketPlusHandoffResponse from(MarketPlusHandoff handoff, String marketLabel) {
		return new MarketPlusHandoffResponse(
			handoff.marketType().name(),
			handoff.cafe24ProductCode(),
			MARKETPLUS_NO_SALE_URL,
			"마켓플러스에서 검색조건을 '상품코드'로 바꾸고 " + handoff.cafe24ProductCode()
				+ "을 붙여넣어 검색한 뒤, " + marketLabel + "을 선택해 일괄 보내기 하세요. "
				+ "카테고리는 전송 팝업에서 상품마다 선택해야 합니다.");
	}
}
