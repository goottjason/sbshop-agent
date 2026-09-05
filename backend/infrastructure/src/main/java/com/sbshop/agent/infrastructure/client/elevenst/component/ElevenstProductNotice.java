package com.sbshop.agent.infrastructure.client.elevenst.component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public final class ElevenstProductNotice {

	public enum NoticeType {
		HEALTH_FUNCTIONAL_FOOD,
		PROCESSED_FOOD
	}

	public record NoticeItem(String code, String label) {
	}

	public record NoticeSpec(String typeCode, List<NoticeItem> items) {

		public boolean isResolved() {
			if (typeCode == null || typeCode.isBlank() || items == null || items.isEmpty()) {
				return false;
			}
			return items.stream().allMatch(i -> i.code() != null && !i.code().isBlank());
		}
	}

	public static final String PLACEHOLDER_VALUE = "상세설명 참조";

	private static final Map<NoticeType, NoticeSpec> SPECS = new EnumMap<>(Map.of(
		NoticeType.HEALTH_FUNCTIONAL_FOOD, new NoticeSpec("", List.of()),
		NoticeType.PROCESSED_FOOD, new NoticeSpec("", List.of())));

	private ElevenstProductNotice() {
	}

	public static NoticeSpec specOf(NoticeType type) {
		return SPECS.get(type);
	}

	public static String buildBlock(NoticeSpec spec, Map<String, String> values) {
		if (spec == null || !spec.isResolved()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("<ProductNotification>");
		sb.append("<type>").append(spec.typeCode()).append("</type>");
		for (NoticeItem item : spec.items()) {
			sb.append("<item><code>").append(item.code()).append("</code>")
				.append("<name><![CDATA[").append(valueFor(values, item.label())).append("]]></name></item>");
		}
		return sb.append("</ProductNotification>").toString();
	}

	public static String inject(String xml, NoticeSpec spec, Map<String, String> values) {
		if (xml == null || spec == null || !spec.isResolved()) {
			return xml;
		}
		String block = buildBlock(spec, values);
		if (block.isEmpty()) {
			return xml;
		}
		String out = xml
			.replaceAll("(?s)<ProductNotification>.*?</ProductNotification>", "")
			.replaceAll("<ProductNotification\\s*/>", "");
		if (!out.contains("</Product>")) {
			return out + block;
		}
		int last = out.lastIndexOf("</Product>");
		return out.substring(0, last) + Matcher.quoteReplacement(block) + out.substring(last);
	}

	private static String valueFor(Map<String, String> values, String label) {
		if (values == null) {
			return PLACEHOLDER_VALUE;
		}
		String v = values.get(label);
		return v == null || v.isBlank() ? PLACEHOLDER_VALUE : v;
	}
}
