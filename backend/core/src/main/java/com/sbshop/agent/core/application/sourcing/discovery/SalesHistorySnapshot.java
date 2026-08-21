package com.sbshop.agent.core.application.sourcing.discovery;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public record SalesHistorySnapshot(
	Map<String, Long> brandQuantity,
	Map<String, Long> categoryQuantity,
	long maxBrandQuantity,
	long maxCategoryQuantity) {
	public static SalesHistorySnapshot empty() {
		return new SalesHistorySnapshot(Map.of(), Map.of(), 0, 0);
	}

	public static SalesHistorySnapshot of(Map<String, Long> brands, Map<String, Long> categories) {
		Map<String, Long> normalizedBrands = new HashMap<>();
		brands.forEach((k, v) -> normalizedBrands.put(key(k), v));
		long maxBrand = normalizedBrands.values().stream().mapToLong(Long::longValue).max().orElse(0);
		long maxCategory = categories.values().stream().mapToLong(Long::longValue).max().orElse(0);
		return new SalesHistorySnapshot(normalizedBrands, categories, maxBrand, maxCategory);
	}

	public double brandScore(String brand) {
		if (brand == null || maxBrandQuantity <= 0)
			return 0;
		Long qty = brandQuantity.get(key(brand));
		return qty == null ? 0 : (double)qty / maxBrandQuantity;
	}

	public double categoryScore(String category) {
		if (category == null || maxCategoryQuantity <= 0)
			return 0;
		Long qty = categoryQuantity.get(category);
		return qty == null ? 0 : (double)qty / maxCategoryQuantity;
	}

	public boolean isEmpty() {
		return maxBrandQuantity <= 0 && maxCategoryQuantity <= 0;
	}

	private static String key(String brand) {
		return brand == null ? "" : brand.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
	}
}
