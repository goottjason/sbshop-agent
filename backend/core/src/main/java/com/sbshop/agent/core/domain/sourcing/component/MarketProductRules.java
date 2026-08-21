package com.sbshop.agent.core.domain.sourcing.component;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;

public final class MarketProductRules {
	private static final String FORBIDDEN_NAME_CHARS = "[<>\"'\\\\{}\\[\\]|^~`®™©]";

	private static final Map<MarketType, Rules> RULES = Map.of(

		MarketType.COUPANG, new Rules(100, 20, 20),

		MarketType.SMART_STORE, new Rules(100, 10, 20),

		MarketType.ELEVEN_STREET, new Rules(100, 10, 20),

		MarketType.CAFE24, new Rules(250, 20, 30));

	private static final Rules DEFAULT = new Rules(100, 10, 20);

	private MarketProductRules() {}

	public static Rules of(MarketType marketType) {
		return RULES.getOrDefault(marketType, DEFAULT);
	}

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

	public record Rules(int nameMaxLength, int maxKeywords, int keywordMaxLength) {
	}
}
