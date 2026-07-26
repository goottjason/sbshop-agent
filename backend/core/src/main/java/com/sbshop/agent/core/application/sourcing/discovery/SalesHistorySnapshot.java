package com.sbshop.agent.core.application.sourcing.discovery;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 최근 자사 판매 실적 스냅샷 — 스코어링 1회차 동안 재사용한다.
 *
 * <p>후보마다 DB를 치면 수백 번 집계 쿼리가 돈다. 회차 시작 때 한 번 집계해 메모리에 들고 다닌다.
 *
 * <p>정규화는 "최대값 대비 비율"이다. 절대 판매량은 사업 규모에 따라 달라지지만, 추천은
 * "우리 기준으로 잘 팔리는 축인가"만 알면 되므로 상대값이면 충분하다.
 */
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

	/** 0.0~1.0. 이력이 없으면 0. */
	public double brandScore(String brand) {
		if (brand == null || maxBrandQuantity <= 0)
			return 0;
		Long qty = brandQuantity.get(key(brand));
		return qty == null ? 0 : (double)qty / maxBrandQuantity;
	}

	/** 0.0~1.0. 이력이 없으면 0. */
	public double categoryScore(String category) {
		if (category == null || maxCategoryQuantity <= 0)
			return 0;
		Long qty = categoryQuantity.get(category);
		return qty == null ? 0 : (double)qty / maxCategoryQuantity;
	}

	public boolean isEmpty() {
		return maxBrandQuantity <= 0 && maxCategoryQuantity <= 0;
	}

	/** 브랜드 표기 흔들림(대소문자·공백) 흡수. */
	private static String key(String brand) {
		return brand == null ? "" : brand.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
	}
}
