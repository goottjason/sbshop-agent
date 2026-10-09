package com.sbshop.agent.infrastructure.client.coupang.component;

import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangAttributeMeta;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoupangPurchaseOptionRepairer {

	public record Result(List<Map<String, Object>> attributes, List<String> filled, List<String> removed,
		List<String> missing) {
	}

	private enum Kind {
		COUNT, WEIGHT, VOLUME, QUANTITY
	}

	private static final Pattern METRIC_WEIGHT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(kg|g)(?![a-zA-Z])",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern POUND = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*lbs?(?![a-zA-Z])",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern OUNCE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(fl\\.?\\s*)?oz(?![a-zA-Z])",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern METRIC_VOLUME = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ml|l)(?![a-zA-Z])",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern KOREAN_COUNT = Pattern
		.compile("(\\d+)\\s*(베지캡슐|캡슐|소프트젤|정|타블렛|구미|츄|개입|포|티백)");
	private static final Pattern ENGLISH_COUNT = Pattern.compile(
		"(\\d+)\\s*(veg(?:etarian|gie)?\\s*caps(?:ules)?|vegcaps|capsules?|caps|softgels?|tablets?|gummies|chews"
			+ "|lozenges|tea bags|packets|sticks)(?![a-zA-Z])",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern BUNDLE_SUFFIX = Pattern.compile("(\\d+)\\s*(개|팩|병|통|봉|박스)\\s*$");
	private static final Pattern BUNDLE_PREFIX = Pattern.compile("^\\s*\\((\\d+)\\s*(개|팩|병|통|봉|박스)\\)");
	private static final Set<String> PLACEHOLDER_VALUES = Set.of("수량", "용량", "중량", "정", "개", "캡슐");
	private static final Set<String> PILL_UNITS = Set.of("베지캡슐", "캡슐", "소프트젤", "타블렛", "구미", "츄");

	public Result repair(List<Map<String, Object>> attributes, List<CoupangAttributeMeta> metas, String itemName,
		String sellerProductName, String originalName) {
		String koreanName = (text(itemName) + " " + text(sellerProductName)).trim();
		Map<String, CoupangAttributeMeta> metaByType = new LinkedHashMap<>();
		for (CoupangAttributeMeta meta : metas)
			metaByType.putIfAbsent(meta.typeName(), meta);
		List<Map<String, Object>> kept = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		Set<String> present = new LinkedHashSet<>();
		for (Map<String, Object> attribute : attributes == null ? List.<Map<String, Object>>of() : attributes) {
			String type = text(attribute.get("attributeTypeName"));
			String value = text(attribute.get("attributeValueName"));
			if (value.isEmpty()) {
				removed.add(type + "(빈값)");
			} else if (!metaByType.containsKey(type)) {
				removed.add(type + "(폐지)");
			} else if (isPlaceholderValue(type, value) || isUnitOnly(value, metaByType.get(type))) {
				removed.add(type + "(쓰레기값)");
			} else {
				kept.add(attribute);
				present.add(type);
			}
		}
		List<String> filled = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (List<CoupangAttributeMeta> group : purchaseOptionGroups(metas)) {
			if (group.stream().anyMatch(m -> present.contains(m.typeName())))
				continue;
			boolean satisfied = false;
			for (CoupangAttributeMeta member : group) {
				String value = kindOf(member.typeName()) == Kind.QUANTITY
					? quantity(itemName, sellerProductName, member.usableUnits())
					: extract(member, koreanName);
				if (value == null)
					value = extract(member, originalName);
				if (value != null) {
					kept.add(newAttribute(member.typeName(), value));
					filled.add(member.typeName() + "=" + value);
					satisfied = true;
					break;
				}
			}
			if (!satisfied)
				group.forEach(m -> missing.add(m.typeName()));
		}
		return new Result(kept, filled, removed, missing);
	}

	public static boolean isPlaceholderValue(String typeName, String valueName) {
		return valueName.equals(typeName) || PLACEHOLDER_VALUES.contains(valueName);
	}

	private static boolean isUnitOnly(String value, CoupangAttributeMeta meta) {
		return meta.usableUnits() != null && usableSpelling(meta.usableUnits(), value) != null;
	}

	private static List<List<CoupangAttributeMeta>> purchaseOptionGroups(List<CoupangAttributeMeta> metas) {
		Map<String, List<CoupangAttributeMeta>> groups = new LinkedHashMap<>();
		for (CoupangAttributeMeta meta : metas) {
			if (!meta.mandatory() || !meta.exposed())
				continue;
			String group = meta.groupNumber();
			String key = group == null || group.isBlank() || "NONE".equalsIgnoreCase(group)
				? "type:" + meta.typeName() : "group:" + group;
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(meta);
		}
		return new ArrayList<>(groups.values());
	}

	private static Kind kindOf(String type) {
		if (type.contains("캡슐") || type.endsWith("정"))
			return Kind.COUNT;
		if (type.contains("중량"))
			return Kind.WEIGHT;
		if (type.contains("용량"))
			return Kind.VOLUME;
		if (type.contains("수량") && !type.contains("개당"))
			return Kind.QUANTITY;
		return null;
	}

	private static String extract(CoupangAttributeMeta meta, String name) {
		if (name == null || name.isBlank())
			return null;
		Kind kind = kindOf(meta.typeName());
		if (kind == null || kind == Kind.QUANTITY)
			return null;
		List<String> units = meta.usableUnits() == null ? List.of() : meta.usableUnits();
		return switch (kind) {
			case WEIGHT -> weight(name, units);
			case VOLUME -> volume(name, units);
			case COUNT -> count(name, units);
			case QUANTITY -> null;
		};
	}

	private static String weight(String name, List<String> units) {
		Matcher m = METRIC_WEIGHT.matcher(name);
		while (m.find()) {
			BigDecimal amount = new BigDecimal(m.group(1));
			String value = m.group(2).equalsIgnoreCase("kg")
				? firstUsable(units, amount, "kg", amount.multiply(BigDecimal.valueOf(1000)), "g")
				: firstUsable(units, amount, "g", null, null);
			if (value != null)
				return value;
		}
		m = POUND.matcher(name);
		while (m.find()) {
			String value = firstUsable(units, grams(m.group(1), "453.59"), "g", null, null);
			if (value != null)
				return value;
		}
		m = OUNCE.matcher(name);
		while (m.find()) {
			if (m.group(2) != null)
				continue;
			String value = firstUsable(units, grams(m.group(1), "28.35"), "g", null, null);
			if (value != null)
				return value;
		}
		return null;
	}

	private static String volume(String name, List<String> units) {
		Matcher m = METRIC_VOLUME.matcher(name);
		while (m.find()) {
			BigDecimal amount = new BigDecimal(m.group(1));
			String value = m.group(2).equalsIgnoreCase("l")
				? firstUsable(units, amount, "L", amount.multiply(BigDecimal.valueOf(1000)), "ml")
				: firstUsable(units, amount, "ml", null, null);
			if (value != null)
				return value;
		}
		m = OUNCE.matcher(name);
		while (m.find()) {
			if (m.group(2) == null)
				continue;
			String value = firstUsable(units, grams(m.group(1), "29.57"), "ml", null, null);
			if (value != null)
				return value;
		}
		return null;
	}

	private static String count(String name, List<String> units) {
		for (Pattern pattern : List.of(KOREAN_COUNT, ENGLISH_COUNT)) {
			Matcher m = pattern.matcher(name);
			while (m.find()) {
				String unit = countUnit(m.group(2), units);
				if (unit != null)
					return Integer.parseInt(m.group(1)) + unit;
			}
		}
		return null;
	}

	private static String countUnit(String matched, List<String> units) {
		String unit = normalizeCountUnit(matched);
		String usable = usableSpelling(units, unit);
		if (usable != null)
			return usable;
		if (PILL_UNITS.contains(unit))
			return usableSpelling(units, "정");
		if ("개입".equals(unit))
			return usableSpelling(units, "개");
		return null;
	}

	private static String normalizeCountUnit(String matched) {
		String lower = matched.toLowerCase();
		if (lower.contains("cap"))
			return "캡슐";
		if (lower.startsWith("softgel"))
			return "소프트젤";
		if (lower.startsWith("tablet") || lower.startsWith("lozenge"))
			return "타블렛";
		if (lower.equals("gummies"))
			return "구미";
		if (lower.equals("chews"))
			return "츄";
		if (lower.equals("tea bags"))
			return "티백";
		if (lower.equals("packets") || lower.equals("sticks"))
			return "포";
		return matched;
	}

	private static String quantity(String itemName, String sellerProductName, List<String> units) {
		String unit = usableSpelling(units == null ? List.of() : units, "개");
		if (unit == null)
			return null;
		Matcher suffix = BUNDLE_SUFFIX.matcher(text(itemName));
		if (suffix.find())
			return Integer.parseInt(suffix.group(1)) + unit;
		for (String name : List.of(text(sellerProductName), text(itemName))) {
			Matcher prefix = BUNDLE_PREFIX.matcher(name);
			if (prefix.find())
				return Integer.parseInt(prefix.group(1)) + unit;
		}
		return 1 + unit;
	}

	private static BigDecimal grams(String amount, String factor) {
		return new BigDecimal(amount).multiply(new BigDecimal(factor)).setScale(0, RoundingMode.HALF_UP);
	}

	private static String firstUsable(List<String> units, BigDecimal amount, String unit, BigDecimal altAmount,
		String altUnit) {
		String usable = usableSpelling(units, unit);
		if (usable != null)
			return number(amount) + usable;
		if (altUnit != null) {
			usable = usableSpelling(units, altUnit);
			if (usable != null)
				return number(altAmount) + usable;
		}
		return null;
	}

	private static String usableSpelling(List<String> units, String unit) {
		for (String usable : units) {
			if (usable != null && usable.trim().equalsIgnoreCase(unit))
				return usable.trim();
		}
		return null;
	}

	private static String number(BigDecimal value) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
	}

	private static Map<String, Object> newAttribute(String type, String value) {
		Map<String, Object> attribute = new LinkedHashMap<>();
		attribute.put("attributeTypeName", type);
		attribute.put("attributeValueName", value);
		attribute.put("exposed", "EXPOSED");
		attribute.put("editable", true);
		return attribute;
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}
}
