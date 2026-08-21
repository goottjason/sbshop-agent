package com.sbshop.agent.core.domain.market.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record MarketPublishContext(
	String categoryId,
	String categoryPath,
	BigDecimal salePrice,
	List<String> keywords,
	Map<String, String> noticeFields,
	Map<String, Object> extraFields) {

	public static MarketPublishContext empty() {
		return new MarketPublishContext(null, null, null, List.of(), Map.of(), Map.of());
	}

	public String extraString(String key) {
		Object v = extraFields.get(key);
		return v == null ? null : String.valueOf(v);
	}

	public Integer extraInt(String key, Integer fallback) {
		Object v = extraFields.get(key);
		if (v == null)
			return fallback;
		try {
			return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public boolean hasCategory() {
		return categoryId != null && !categoryId.isBlank();
	}
}
