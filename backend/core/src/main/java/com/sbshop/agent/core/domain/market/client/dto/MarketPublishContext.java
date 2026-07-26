package com.sbshop.agent.core.domain.market.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 신규 등록 시 {@link com.sbshop.agent.core.domain.product.Product}만으로는 채울 수 없는
 * <b>마켓별 등록 데이터</b>.
 *
 * <p>카테고리·검색키워드·상품고시정보·출고지 코드 같은 값은 마켓마다 스키마도 값도 다르고,
 * 사용자가 검수 화면에서 고칠 수 있어야 한다. 그래서 {@code Product}에 욱여넣지 않고
 * {@code MarketDraft}에 담아 두었다가 게시 시점에 이 컨텍스트로 전달한다.
 *
 * <p>컨텍스트 없이 {@code publish(product)}를 부르면 각 어댑터의 기존 기본 동작이 그대로 쓰인다
 * (카테고리 자동 예측 등) — 기존 호출 경로는 깨지지 않는다.
 */
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
