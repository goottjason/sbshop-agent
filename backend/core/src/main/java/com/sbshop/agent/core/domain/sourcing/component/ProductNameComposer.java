package com.sbshop.agent.core.domain.sourcing.component;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;

/**
 * 마켓 상품명 조립 — {@code [브랜드] [핵심명] [용량단위] [묶음수]}.
 *
 * <p>기존 {@code Product.assembleMarketName()}은
 * {@code "%s %s, %d%s, %d개"} 포맷을 그대로 찍어 iHerb 영문명이 상품명에 그대로 들어갔다.
 * 여기서는 LLM(또는 규칙)이 만든 <b>한글 핵심명</b>을 받아 조립하고, 마켓별 길이에 맞춘다.
 *
 * <p>묶음수는 1개일 때 표기하지 않는다 — "1개"는 검색에 도움이 안 되고 글자수만 먹는다.
 */
public final class ProductNameComposer {

	private ProductNameComposer() {
	}

	public static String compose(String brandKo, String baseName, BigDecimal capacity,
		String unitDesc, int bundleQty, MarketType marketType) {
		StringBuilder sb = new StringBuilder();
		append(sb, brandKo);
		append(sb, baseName);
		append(sb, capacityPart(capacity, unitDesc));
		if (bundleQty > 1)
			append(sb, bundleQty + "개");

		return MarketProductRules.fitName(sb.toString(), marketType);
	}

	private static String capacityPart(BigDecimal capacity, String unitDesc) {
		if (capacity == null || capacity.signum() <= 0)
			return null;
		// 180.00 → "180" (소수점 표기는 상품명에서 지저분하다)
		String num = capacity.stripTrailingZeros().toPlainString();
		return unitDesc == null || unitDesc.isBlank() ? num : num + unitDesc;
	}

	private static void append(StringBuilder sb, String part) {
		if (part == null || part.isBlank())
			return;
		if (sb.length() > 0)
			sb.append(' ');
		sb.append(part.trim());
	}
}
