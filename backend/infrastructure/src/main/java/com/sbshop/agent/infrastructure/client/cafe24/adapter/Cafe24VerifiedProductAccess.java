package com.sbshop.agent.infrastructure.client.cafe24.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact shop/product/variant observations. Local cached values never authorize a write. */
final class Cafe24VerifiedProductAccess {
	private final ObjectMapper mapper;
	private final Cafe24RestClient rest;
	private final String id;
	private final String account;

	Cafe24VerifiedProductAccess(ObjectMapper mapper, Cafe24RestClient rest, String id) {
		if (id == null || !id.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("카페24 상품 번호가 올바르지 않습니다.");
		this.mapper = mapper;
		this.rest = rest;
		this.id = id;
		this.account = rest.accountReference();
		sameAccount();
	}

	String path() {
		return "/admin/products/" + id;
	}

	void sameAccount() {
		if (account == null || account.isBlank() || !Objects.equals(account, rest.accountReference()))
			throw new IllegalStateException("카페24 조회·전송 계정이 변경되었거나 확인되지 않았습니다.");
	}

	JsonNode product() {
		JsonNode p = object(path() + "?shop_no=1", "product");
		if (!id.equals(p.path("product_no").asText())
			|| !p.path("product_code").asText().matches("P[A-Z0-9]{7}"))
			throw new IllegalStateException("카페24 응답 상품번호·상품코드가 조회 대상과 일치하지 않습니다.");
		return p;
	}

	void requireNativeWrite(JsonNode p) {
		sameAccount();
		if (!"F".equals(p.path("market_sync").asText()))
			throw new UnsupportedOperationException("카페24 마켓플러스 연동 상품은 G마켓·옥션 전달 범위·결과 확인이 필요해 전송을 보류합니다.");
		if (!List.of("T", "F").contains(p.path("selling").asText()))
			throw new IllegalStateException("카페24 판매 상태를 확인할 수 없습니다.");
	}

	String singleVariant(JsonNode product) {
		JsonNode root = read(path() + "/variants?shop_no=1");
		JsonNode variants = root.path("variants");
		if (!variants.isArray() || variants.size() != 1)
			throw new UnsupportedOperationException("카페24 variant_code 대응 확인 필요: 단일 품목이 아니므로 첫 품목을 임의로 수정하지 않습니다.");
		JsonNode v = variants.get(0);
		String code = v.path("variant_code").asText();
		if (!v.isObject() || !isShopOne(v) || !code.matches("P[A-Z0-9]{11}")
			|| !code.startsWith(product.path("product_code").asText()))
			throw new IllegalStateException("카페24 품목 코드·쇼핑몰이 조회 상품과 일치하지 않습니다.");
		return code;
	}

	JsonNode variant(String code) {
		JsonNode v = object(path() + "/variants/" + code + "?shop_no=1", "variant");
		requireVariant(v, code);
		return v;
	}

	JsonNode inventory(String code) {
		JsonNode v = object(path() + "/variants/" + code + "/inventories?shop_no=1", "inventory");
		requireVariant(v, code);
		quantity(v);
		for (String flag : List.of("use_inventory", "display_soldout"))
			if (!List.of("T", "F").contains(v.path(flag).asText()))
				throw new IllegalStateException("카페24 재고 설정을 확인할 수 없습니다: " + flag);
		return v;
	}

	int quantity(JsonNode inventory) {
		JsonNode q = inventory.path("quantity");
		try {
			if (q.isNumber() || q.isTextual())
				return new java.math.BigDecimal(q.asText()).intValueExact();
		} catch (ArithmeticException | NumberFormatException e) {
			throw new IllegalStateException("카페24 재고수량 응답이 유효한 정수가 아닙니다.");
		}
		throw new IllegalStateException("카페24 재고수량 응답이 없습니다.");
	}

	void put(String path, Map<String, Object> fields) {
		sameAccount();
		String response = rest.put(path, Map.of("shop_no", 1, "request", fields));
		// A successful transport response remains unconfirmed until a separate GET matches.
		if (response != null && !response.isBlank()) {
			try {
				JsonNode body = mapper.readTree(response);
				if (body != null && body.has("error"))
					throw new IllegalStateException("카페24 전송 응답에 업무 오류가 있습니다. 재조회 후 재시도하세요.");
			} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
				throw new IllegalStateException("카페24 전송 응답을 해석하지 못했습니다. 재조회 후 재시도하세요.", e);
			}
		}
		sameAccount();
	}

	Map<String, Object> snapshot(Map<String, Object> previous, JsonNode product, JsonNode variant,
		JsonNode inventory, List<String> verifiedFields) {
		sameAccount();
		Map<String, Object> result = previous == null ? new LinkedHashMap<>() : new LinkedHashMap<>(previous);
		if (product != null)
			result.putAll(mapper.convertValue(product, Map.class));
		Map<String, Object> v = mapper.convertValue(variant, Map.class);
		if (inventory != null)
			v.putAll(mapper.convertValue(inventory, Map.class));
		result.put("variants", List.of(v));
		result.put("_sbshop_verified_fields", Map.of("fields", verifiedFields,
			"checkedAt", java.time.Instant.now().toString(), "product_no", id,
			"variant_code", variant.path("variant_code").asText(), "scope", "CAFE24_NATIVE_ONLY"));
		return result;
	}

	private JsonNode object(String path, String field) {
		JsonNode node = read(path).path(field);
		if (!node.isObject() || !isShopOne(node))
			throw new IllegalStateException("카페24 " + field + " 응답·쇼핑몰을 확인하지 못했습니다.");
		return node;
	}

	private JsonNode read(String path) {
		sameAccount();
		try {
			JsonNode root = mapper.readTree(rest.get(path));
			if (root == null || !root.isObject() || root.has("error"))
				throw new IllegalStateException("카페24 조회 응답이 없거나 업무 오류가 있습니다.");
			sameAccount();
			return root;
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("카페24 조회 응답을 해석하지 못했습니다.", e);
		}
	}

	private boolean isShopOne(JsonNode node) {
		return "1".equals(node.path("shop_no").asText());
	}

	private void requireVariant(JsonNode node, String code) {
		if (!code.equals(node.path("variant_code").asText()))
			throw new IllegalStateException("카페24 재조회 품목 코드가 수정 대상과 다릅니다.");
	}
}
