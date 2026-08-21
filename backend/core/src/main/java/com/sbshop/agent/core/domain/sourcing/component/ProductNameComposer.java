package com.sbshop.agent.core.domain.sourcing.component;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.util.regex.Pattern;

public final class ProductNameComposer {
	private static final Pattern HAS_SIZE = Pattern.compile(
		"[0-9][0-9,.]*\\s*(g|kg|mg|ml|l|oz|lb|정|개|캡슐|소프트젤|포|정제|티백|스쿱|회분)",
		Pattern.CASE_INSENSITIVE);

	private ProductNameComposer() {}

	public static String compose(String brandKo, String baseName, BigDecimal capacity,
		String unitDesc, int bundleQty, MarketType marketType) {
		StringBuilder sb = new StringBuilder();
		append(sb, brandKo);
		append(sb, baseName);

		if (baseName == null || !HAS_SIZE.matcher(baseName).find())
			append(sb, capacityPart(capacity, unitDesc));
		if (bundleQty > 1)
			append(sb, bundleQty + "개");

		return MarketProductRules.fitName(sb.toString(), marketType);
	}

	private static String capacityPart(BigDecimal capacity, String unitDesc) {
		if (capacity == null || capacity.signum() <= 0)
			return null;

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
