package com.sbshop.agent.core.domain.sourcing.component;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;

/**
 * 마켓별 상품 등록 규칙 — 상품명 길이, 키워드 개수 등.
 *
 * <p>마켓 API가 400으로 거절하고 나서 알아내는 대신, 초안 생성 단계에서 미리 맞춘다.
 * 값을 한곳에 모아 두면 마켓 스펙이 바뀔 때 고칠 자리가 하나다.
 */
public final class MarketProductRules {

	/** 마켓이 상품명에서 거부하거나 검색에 방해가 되는 문자. */
	private static final String FORBIDDEN_NAME_CHARS = "[<>\"'\\\\{}\\[\\]|^~`®™©]";

	private static final Map<MarketType, Rules> RULES = Map.of(
		// 쿠팡 displayProductName 100자. searchTags 최대 20개.
		MarketType.COUPANG, new Rules(100, 20, 20),
		// 스마트스토어 originProduct.name 100자. 태그(sellerTags) 최대 10개.
		MarketType.SMART_STORE, new Rules(100, 10, 20),
		// 11번가 prdNm 100자. 키워드(sellerPrdCd 연관검색어) 최대 10개.
		MarketType.ELEVEN_STREET, new Rules(100, 10, 20),
		// Cafe24 product_name 250자. 자사몰이라 여유가 크다.
		MarketType.CAFE24, new Rules(250, 20, 30));

	private static final Rules DEFAULT = new Rules(100, 10, 20);

	private MarketProductRules() {}

	public static Rules of(MarketType marketType) {
		return RULES.getOrDefault(marketType, DEFAULT);
	}

	/**
	 * 상품명을 마켓 규칙에 맞춘다.
	 *
	 * <p>단순 substring으로 자르면 단어 중간이 끊겨 "…비타민D3 프리미" 같은 이름이 된다.
	 * 마지막 공백에서 자르되, 그러면 절반 이상이 날아가는 경우엔 그냥 자른다.
	 */
	public static String fitName(String name, MarketType marketType) {
		if (name == null)
			return "";
		String cleaned = name.replaceAll(FORBIDDEN_NAME_CHARS, " ")
			.replaceAll("\\s+", " ")
			.trim();
		int max = of(marketType).nameMaxLength();
		if (cleaned.length() <= max)
			return cleaned;

		String cut = cleaned.substring(0, max);
		int lastSpace = cut.lastIndexOf(' ');
		if (lastSpace > max / 2)
			cut = cut.substring(0, lastSpace);
		return cut.trim();
	}

	/**
	 * @param nameMaxLength    상품명 최대 길이
	 * @param maxKeywords      검색 키워드 최대 개수
	 * @param keywordMaxLength 키워드 1개의 최대 길이
	 */
	public record Rules(int nameMaxLength, int maxKeywords, int keywordMaxLength) {
	}
}
