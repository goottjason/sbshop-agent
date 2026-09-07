package com.sbshop.agent.infrastructure.client.cafe24.adapter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Cafe24 native fields. Marketplace Plus child products require their own independent evidence. */
final class Cafe24ReviewedFields {
	static final Set<String> FIELDS = Set.of("name", "brand", "weight", "barcode", "detailHtml");
	private final Cafe24RestClient rest;
	private final ObjectMapper mapper;

	Cafe24ReviewedFields(Cafe24RestClient rest, ObjectMapper mapper) {
		this.rest = rest;
		this.mapper = mapper;
	}

	PreparedMarketFields prepare(Product product, String id, String option, Set<String> fields) {
		checkFields(fields);
		var access = new Cafe24VerifiedProductAccess(mapper, rest, id);
		JsonNode p = identity(access, product.getSbCode());
		access.requireNativeWrite(p);
		String variant = fields.contains("barcode") ? access.singleVariant(p) : null;
		ObjectNode delta = mapper.createObjectNode();
		Map<String, String> expected = new LinkedHashMap<>();
		for (String field : fields) {
			String value = switch (field) {
				case "name" -> product.getProductName();
				case "brand" -> existingBrand(product.getBrand());
				case "weight" ->
					decimal(product.getLogisticsInfo() == null ? null : product.getLogisticsInfo().getWeight());
				case "barcode" -> product.getProductSpec() == null ? null : product.getProductSpec().getBarcode();
				case "detailHtml" -> product.getDetailHtml();
				default -> throw new IllegalArgumentException("지원하지 않는 필드");
			};
			if (value == null || value.isBlank())
				throw new IllegalArgumentException("카페24 검토 필드 값이 비어 있습니다: " + field);
			if (field.equals("name") && value.length() > 250)
				throw new IllegalArgumentException("카페24 상품명 길이를 확인하세요.");
			if (field.equals("barcode") && !value.matches("[0-9]{1,14}"))
				throw new IllegalArgumentException("카페24 GTIN은 1~14자리 숫자여야 합니다.");
			expected.put(field, value);
			delta.put(field, value);
		}
		access.sameAccount();
		return new PreparedMarketFields(rest.accountReference(), variant == null ? "PRODUCT:" + id : variant, false,
			expected, delta.toString());
	}

	MarketFieldsRead read(String id, String option, String sb, Set<String> fields) {
		checkFields(fields);
		var access = new Cafe24VerifiedProductAccess(mapper, rest, id);
		JsonNode p = identity(access, sb);
		String variant = fields.contains("barcode") ? access.singleVariant(p) : null;
		JsonNode v = variant == null ? null : access.variant(variant);
		Map<String, String> values = new LinkedHashMap<>();
		for (String field : fields) {
			JsonNode value = field.equals("barcode") ? v.path("gtin") : p.path(apiField(field));
			if (!value.isValueNode() || value.isNull())
				throw new IllegalStateException("카페24 실제 필드 값이 없습니다: " + field);
			values.put(field, field.equals("weight") ? decimal(new BigDecimal(value.asText())) : value.asText());
		}
		String blocked = null;
		try {
			access.requireNativeWrite(p);
		} catch (UnsupportedOperationException | IllegalStateException e) {
			blocked = e.getMessage();
		}
		access.sameAccount();
		return new MarketFieldsRead(values, rest.accountReference(), variant == null ? "PRODUCT:" + id : variant,
			MarketFieldsRead.Approval.NOT_REQUIRED, blocked);
	}

	void write(String id, String option, String sb, PreparedMarketFields prepared, Runnable beforeWrite) {
		checkFields(prepared.expectedValues().keySet());
		if (!Objects.equals(prepared.accountReference(), rest.accountReference()))
			throw new IllegalStateException("카페24 계정이 변경되었습니다.");
		var access = new Cafe24VerifiedProductAccess(mapper, rest, id);
		JsonNode p = identity(access, sb);
		access.requireNativeWrite(p);
		JsonNode delta = parse(prepared.payload());
		Set<String> keys = new HashSet<>();
		delta.fieldNames().forEachRemaining(keys::add);
		if (!keys.equals(prepared.expectedValues().keySet()))
			throw new IllegalArgumentException("검토 필드와 요청 필드가 다릅니다.");
		Map<String, Object> productFields = new LinkedHashMap<>();
		String variant = null;
		String barcode = null;
		for (String field : keys) {
			String value = delta.path(field).asText();
			if (!Objects.equals(value, prepared.expectedValues().get(field)))
				throw new IllegalArgumentException("검토한 목표값이 바뀌었습니다.");
			if (field.equals("barcode")) {
				variant = access.singleVariant(p);
				if (!variant.equals(prepared.resolvedOptionId()))
					throw new IllegalStateException("카페24 검토 품목이 변경되었습니다.");
				if (!value.equals(access.variant(variant).path("gtin").asText()))
					barcode = value;
			} else {
				String actual = p.path(apiField(field)).asText();
				if (field.equals("weight"))
					actual = decimal(new BigDecimal(actual));
				if (!value.equals(actual))
					productFields.put(apiField(field), field.equals("weight") ? new BigDecimal(value) : value);
			}
		}
		if (variant == null && !("PRODUCT:" + id).equals(prepared.resolvedOptionId()))
			throw new IllegalStateException("카페24 검토 상품이 변경되었습니다.");
		if (!productFields.isEmpty()) {
			beforeWrite.run();
			access.sameAccount();
			access.put(access.path(), productFields);
		}
		if (barcode != null) {
			// Each HTTP mutation is separately guarded; a partial result is independently reread by the task engine.
			beforeWrite.run();
			access.sameAccount();
			access.put(access.path() + "/variants/" + variant, Map.of("gtin", barcode));
		}
	}

	private JsonNode identity(Cafe24VerifiedProductAccess access, String sb) {
		JsonNode p = access.product();
		if (sb == null || sb.isBlank() || !sb.equals(p.path("custom_product_code").asText()))
			throw new IllegalStateException("카페24 SB코드가 일치하지 않습니다.");
		return p;
	}

	private String existingBrand(String name) {
		if (name == null || name.isBlank())
			throw new IllegalArgumentException("브랜드 이름이 필요합니다.");
		String account = rest.accountReference();
		JsonNode brands = parse(
			rest.get("/admin/brands?shop_no=1&brand_name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
			.path("brands");
		if (!brands.isArray() || !Objects.equals(account, rest.accountReference()))
			throw new IllegalStateException("카페24 브랜드 조회·계정을 확인할 수 없습니다.");
		List<String> codes = new ArrayList<>();
		for (JsonNode brand : brands)
			if (name.equals(brand.path("brand_name").asText())
				&& brand.path("brand_code").asText().matches("B[A-Z0-9]{7}"))
				codes.add(brand.path("brand_code").asText());
		if (codes.size() != 1)
			throw new IllegalStateException("일치하는 카페24 브랜드 코드가 하나로 확인되지 않습니다. 브랜드 등록·중복을 먼저 확인하세요.");
		return codes.get(0);
	}

	private static String apiField(String field) {
		return switch (field) {
			case "name" -> "product_name";
			case "brand" -> "brand_code";
			case "weight" -> "product_weight";
			case "detailHtml" -> "description";
			default -> throw new IllegalArgumentException("필드 매핑 없음");
		};
	}

	private static String decimal(BigDecimal value) {
		if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("999999.99")) > 0)
			throw new IllegalArgumentException("카페24 무게 범위를 확인하세요.");
		return value.stripTrailingZeros().toPlainString();
	}

	private static void checkFields(Set<String> fields) {
		if (fields == null || fields.isEmpty() || !FIELDS.containsAll(fields))
			throw new UnsupportedOperationException("카페24에서 검증하지 않은 필드가 포함되어 있습니다.");
	}

	private JsonNode parse(String text) {
		try {
			JsonNode n = mapper.readTree(text);
			if (n == null || !n.isObject() || n.has("error"))
				throw new IllegalStateException("카페24 응답 객체를 확인할 수 없습니다.");
			return n;
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("카페24 응답 해석 실패", e);
		}
	}
}
