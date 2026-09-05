package com.sbshop.agent.infrastructure.client.elevenst.component;

import java.util.EnumMap;
import java.util.HashMap;
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

	public static final String DRUG_DISCLAIMER_CODE = "23759747";

	private static final String DRUG_DISCLAIMER_VALUE = "본 제품은 질병의 예방 및 치료를 위한 의약품이 아닙니다.";

	private static final Map<NoticeType, NoticeSpec> SPECS = new EnumMap<>(Map.of(
		NoticeType.HEALTH_FUNCTIONAL_FOOD, new NoticeSpec("891032", List.of(
			new NoticeItem("176312674", "소비자안전을 위한 주의사항"),
			new NoticeItem("176317774", "제품명"),
			new NoticeItem("42155152", "포장단위별 내용물의 용량(중량), 수량"),
			new NoticeItem("23756446", "섭취량, 섭취방법 및 섭취 시 주의사항 및 부작용 가능성"),
			new NoticeItem("23755783", "기능정보"),
			new NoticeItem("11906", "제조업소의 명칭과 소재지 (수입품의 경우 수입업소명, 제조업소명 및 수출국명)"),
			new NoticeItem("23756963", "수입식품에 해당하는 경우 “수입식품안전관리특별법에 따른 수입신고를 필함”의 문구"),
			new NoticeItem("23756754", "소비자상담 관련 전화번호"),
			new NoticeItem("23759747", "질병의 예방 및 치료를 위한 의약품이 아니라는 내용의 표현"),
			new NoticeItem("23759354", "소비기한 및 보관방법"),
			new NoticeItem("23757103", "영양정보"),
			new NoticeItem("23757245", "원료명 및 함량(｢농수산물의 원산지 표시 등에 관한 법률｣에 따른 원산지 표시 포함)"),
			new NoticeItem("23757304", "｢건강기능식품에 관한 법률｣에 따른 유전자변형건강기능식품 표시 (해당 경우에 한함)"))),
		NoticeType.PROCESSED_FOOD, new NoticeSpec("891031", List.of(
			new NoticeItem("176317774", "제품명"),
			new NoticeItem("176312674", "소비자안전을 위한 주의사항"),
			new NoticeItem("176400445", "생산자 및 소재지 (수입품의 경우 생산자, 수입자 및 제조국)"),
			new NoticeItem("176398001", "제조연월일, 소비기한 또는 품질유지기한"),
			new NoticeItem("23756754", "소비자상담 관련 전화번호"),
			new NoticeItem("23757095", "영양성분 (영양성분 표시대상 식품에 한함)"),
			new NoticeItem("42155152", "포장단위별 내용물의 용량(중량), 수량"),
			new NoticeItem("42154823", "수입식품에 해당하는 경우 “수입식품안전관리특별법에 따른 수입신고를 필함”의 문구"),
			new NoticeItem("23757245", "원료명 및 함량(\"농수산물의 원산지 표시 등에 관한 법률\"에 따른 원산지 표시 포함)"),
			new NoticeItem("23757260", "유전자변형식품의 경우의 표시"),
			new NoticeItem("23757000", "식품의 유형")))));

	private static final Map<String, String> SOURCE_KEY_BY_CODE = new HashMap<>();

	static {
		SOURCE_KEY_BY_CODE.put("11906", "producer");
		SOURCE_KEY_BY_CODE.put("176317774", "productName");
		SOURCE_KEY_BY_CODE.put("176398001", "expirationDate");
		SOURCE_KEY_BY_CODE.put("176400445", "producer");
		SOURCE_KEY_BY_CODE.put("23756446", "intakeMethod");
		SOURCE_KEY_BY_CODE.put("23756754", "customerServiceNumber");
		SOURCE_KEY_BY_CODE.put("23756963", "importDeclaration");
		SOURCE_KEY_BY_CODE.put("23757000", "foodType");
		SOURCE_KEY_BY_CODE.put("23757095", "nutrition");
		SOURCE_KEY_BY_CODE.put("23757103", "nutrition");
		SOURCE_KEY_BY_CODE.put("23757245", "ingredients");
		SOURCE_KEY_BY_CODE.put("23757260", "gmoInfo");
		SOURCE_KEY_BY_CODE.put("23757304", "gmoInfo");
		SOURCE_KEY_BY_CODE.put("23759354", "expirationDate");
		SOURCE_KEY_BY_CODE.put("42154823", "importDeclaration");
		SOURCE_KEY_BY_CODE.put("42155152", "capacity");
	}

	private static final Map<String, String> DEFAULT_VALUE_BY_CODE =
		Map.of(DRUG_DISCLAIMER_CODE, DRUG_DISCLAIMER_VALUE);

	private ElevenstProductNotice() {
	}

	public static NoticeSpec specOf(NoticeType type) {
		return SPECS.get(type);
	}

	public static Map<String, String> valuesFromNoticeFields(Map<String, String> noticeFields) {
		Map<String, String> byLabel = new HashMap<>();
		if (noticeFields == null) {
			return byLabel;
		}
		for (NoticeSpec spec : SPECS.values()) {
			for (NoticeItem item : spec.items()) {
				String sourceKey = SOURCE_KEY_BY_CODE.get(item.code());
				if (sourceKey == null) {
					continue;
				}
				String value = noticeFields.get(sourceKey);
				if (value != null && !value.isBlank()) {
					byLabel.put(item.label(), value);
				}
			}
		}
		return byLabel;
	}

	public static String buildBlock(NoticeSpec spec, Map<String, String> values) {
		if (spec == null || !spec.isResolved()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("<ProductNotification>");
		sb.append("<type>").append(spec.typeCode()).append("</type>");
		for (NoticeItem item : spec.items()) {
			sb.append("<item><code>").append(item.code()).append("</code>")
				.append("<name><![CDATA[").append(valueFor(values, item)).append("]]></name></item>");
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

	private static String valueFor(Map<String, String> values, NoticeItem item) {
		if (values != null) {
			String v = values.get(item.label());
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return DEFAULT_VALUE_BY_CODE.getOrDefault(item.code(), PLACEHOLDER_VALUE);
	}
}
