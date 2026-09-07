package com.sbshop.agent.core.application.product.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.product.enums.*;
import java.net.URI;
import java.util.*;

/** An explicit set of non-numeric values; omitted fields are never cleared. */
public record ProductBulkValuesRequest(@JsonDeserialize(contentUsing = ProductIdDeserializer.class)
List<Long> productIds, ObjectNode values) {
	public static final class ProductIdDeserializer extends JsonDeserializer<Long> {
		@Override
		public Long deserialize(JsonParser parser, DeserializationContext context) throws java.io.IOException {
			if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
				return (Long)context.handleUnexpectedToken(Long.class, parser);
			return parser.getLongValue();
		}
	}

	public record Option(String value, String label) {
	}
	public record Field(String field, String label, String kind, int maxLength, boolean clearable,
		List<Option> options) {
	}

	private static final List<Field> FIELDS = List.of(
		text("brand", "브랜드", 100, true),
		enumeration("category", "카테고리", Arrays.stream(ProductCategory.values()).map(v -> new Option(v.name(),
			switch (v) {
				case SUPPLEMENT -> "영양제";
				case FOOD -> "식품";
				case COSMETICS -> "화장품";
				case UNKNOWN -> "기타";
			})).toList()),
		text("name", "상품명", 255, false), text("baseName", "기본명", 255, false),
		text("originalName", "원문명", 255, true), text("barcode", "바코드", 100, true),
		enumeration("measureUnit", "용량 단위",
			Arrays.stream(MeasureUnit.values()).map(v -> new Option(v.name(), v.getDescription())).toList()),
		enumeration("vendor", "소싱처",
			Arrays.stream(VendorType.values()).map(v -> new Option(v.name(), v.name())).toList()),
		text("manufacturer", "제조사", 100, true), text("origin", "원산지", 100, true), text("hsCode", "HS코드", 50, true),
		new Field("sourceUrl", "소싱 URL", "URL", 1000, true, List.of()),
		text("searchKeywords", "검색어", 500, true), new Field("memo", "메모", "TEXTAREA", 2000, true, List.of()),
		new Field("sourceImages", "원본 이미지 URL 목록", "IMAGES", 1000, true, List.of()),
		new Field("hostedImages", "대표·추가 이미지 URL 목록", "IMAGES", 1000, true, List.of()),
		new Field("detailHtml", "상세 HTML", "HTML", 1_000_000, true, List.of()));

	public ProductBulkValuesRequest {
		if (productIds == null || productIds.isEmpty() || productIds.size() > 500
			|| productIds.stream().anyMatch(id -> id == null || id < 1)
			|| new HashSet<>(productIds).size() != productIds.size())
			throw new IllegalArgumentException("중복 없이 1~500개의 상품을 선택하세요.");
		if (values == null || values.isEmpty())
			throw new IllegalArgumentException("변경할 필드와 값을 선택하세요.");
		for (var entry : values.properties()) {
			Field field = FIELDS.stream().filter(f -> f.field().equals(entry.getKey())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("일괄 값 편집 대상이 아닌 필드: " + entry.getKey()));
			validate(field, entry.getValue());
		}
		int serializedLength = values.toString().length();
		if (serializedLength > 1_100_000 || (long)serializedLength * productIds.size() > 5_000_000)
			throw new IllegalArgumentException("변경값 요청이 너무 큽니다. 이미지·상세정보를 나누어 검토하세요.");
		productIds = List.copyOf(productIds);
		values = values.deepCopy();
	}

	public static List<Field> fields() {
		return FIELDS;
	}

	private static Field text(String field, String label, int length, boolean clearable) {
		return new Field(field, label, "TEXT", length, clearable, List.of());
	}

	private static Field enumeration(String field, String label, List<Option> options) {
		return new Field(field, label, "SELECT", 0, false, options);
	}

	private static void validate(Field field, JsonNode value) {
		if (field.kind().equals("IMAGES")) {
			if (!value.isArray() || value.size() > 100)
				throw new IllegalArgumentException(field.label() + "은 최대 100개 URL 배열로 입력하세요.");
			for (var image : value) {
				if (!image.isTextual() || image.textValue().length() > field.maxLength())
					throw new IllegalArgumentException(field.label() + "의 URL 형식·길이를 확인하세요.");
				httpUrl(image.textValue(), field.label());
			}
			return;
		}
		if (!value.isTextual())
			throw new IllegalArgumentException(field.label() + "은 문자열로 입력하세요.");
		String text = value.textValue();
		if (field.kind().equals("SELECT")) {
			if (field.options().stream().noneMatch(option -> option.value().equals(text)))
				throw new IllegalArgumentException(field.label() + "의 지원 값을 선택하세요.");
		} else if (text.length() > field.maxLength() || (!field.clearable() && text.isBlank())) {
			throw new IllegalArgumentException(field.label() + "의 입력 길이·빈 값을 확인하세요.");
		}
		if (field.kind().equals("URL") && !text.isEmpty())
			httpUrl(text, field.label());
	}

	private static void httpUrl(String value, String label) {
		try {
			URI uri = URI.create(value);
			if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
				|| uri.getUserInfo() != null)
				throw new IllegalArgumentException();
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(label + "은 계정정보 없는 절대 HTTP(S) URL로 입력하세요.");
		}
	}
}
