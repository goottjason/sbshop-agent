package com.sbshop.agent.infrastructure.client.smartstore.adapter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.net.URI;
import java.util.*;
import java.util.function.Function;

/** Exact field deltas over a fresh original-product document; no implicit notice/status repair. */
final class SmartstoreReviewedFields {
	static final Set<String> FIELDS = Set.of("name", "brand", "manufacturer", "barcode", "hostedImages", "detailHtml");
	private final SmartstoreRestClient rest;
	private final ObjectMapper mapper;
	private final Function<List<String>, List<String>> upload;

	SmartstoreReviewedFields(SmartstoreRestClient rest, ObjectMapper mapper,
		Function<List<String>, List<String>> upload) {
		this.rest = rest;
		this.mapper = mapper;
		this.upload = upload;
	}

	PreparedMarketFields prepare(Product p, String id, String option, Set<String> fields) {
		requireFields(fields);
		String account = account();
		ObjectNode current = product(id, p.getSbCode(), account, fields);
		requireWritable(current);
		var values = new LinkedHashMap<String, String>();
		ObjectNode delta = mapper.createObjectNode();
		for (String field : fields) {
			String value = switch (field) {
				case "name" -> p.getProductName();
				case "brand" -> p.getBrand();
				case "manufacturer" -> p.getSourcingInfo() == null ? null : p.getSourcingInfo().getManufacturer();
				case "barcode" -> p.getProductSpec() == null ? null : p.getProductSpec().getBarcode();
				case "detailHtml" -> p.getDetailHtml();
				case "hostedImages" -> null;
				default -> throw new IllegalArgumentException("지원하지 않는 필드");
			};
			if (field.equals("hostedImages")) {
				List<String> urls = p.getHostedImages();
				if (urls == null || urls.isEmpty() || urls.size() > 10)
					throw new IllegalArgumentException("스마트스토어 대표·추가 이미지는 합계 1~10개여야 합니다.");
				urls.forEach(SmartstoreReviewedFields::requireUrl);
				List<String> uploaded = upload.apply(List.copyOf(urls));
				if (uploaded == null || uploaded.size() != urls.size())
					throw new IllegalStateException("이미지 전체 호스팅을 확인하지 못했습니다. 일부만 상품에 적용하지 않습니다.");
				uploaded.forEach(SmartstoreReviewedFields::requireUrl);
				ArrayNode array = mapper.valueToTree(uploaded);
				values.put(field, array.toString());
				delta.set(field, array);
			} else {
				value = Objects.toString(value, "");
				if ((field.equals("name") || field.equals("detailHtml")) && value.isBlank())
					throw new IllegalArgumentException("상품명·상세 HTML은 비울 수 없습니다.");
				if (field.equals("name") && value.length() > 100)
					throw new IllegalArgumentException("스마트스토어 상품명은 100자 이내로 검토하세요.");
				values.put(field, value);
				delta.put(field, value);
			}
		}
		sameAccount(account);
		return new PreparedMarketFields(account, "ORIGIN:" + id, false, values, delta.toString());
	}

	MarketFieldsRead read(String id, String option, String sb, Set<String> fields) {
		requireFields(fields);
		String account = account();
		ObjectNode p = product(id, sb, account, fields);
		Map<String, String> values = values(p, fields);
		String block = null;
		try {
			requireWritable(p);
		} catch (UnsupportedOperationException | IllegalStateException e) {
			block = e.getMessage();
		}
		return new MarketFieldsRead(values, account, "ORIGIN:" + id, MarketFieldsRead.Approval.NOT_REQUIRED, block);
	}

	void write(String id, String option, String sb, PreparedMarketFields prepared, Runnable beforeWrite) {
		requireFields(prepared.expectedValues().keySet());
		sameAccount(prepared.accountReference());
		if (!("ORIGIN:" + id).equals(prepared.resolvedOptionId()))
			throw new IllegalStateException("스마트스토어 원상품 대상이 변경되었습니다.");
		ObjectNode current = product(id, sb, prepared.accountReference(), prepared.expectedValues().keySet());
		requireWritable(current);
		JsonNode delta = parse(prepared.payload());
		Set<String> keys = new HashSet<>();
		delta.fieldNames().forEachRemaining(keys::add);
		if (!keys.equals(prepared.expectedValues().keySet()))
			throw new IllegalArgumentException("검토 필드와 요청 필드가 다릅니다.");
		for (String field : keys) {
			JsonNode value = delta.get(field);
			String canonical = field.equals("hostedImages") ? value.toString() : value.asText();
			if (!prepared.expectedValues().get(field).equals(canonical))
				throw new IllegalArgumentException("검토한 목표값이 바뀌었습니다.");
			switch (field) {
				case "name" -> current.put("name", value.asText());
				case "detailHtml" -> current.put("detailContent", value.asText());
				case "barcode" ->
					current.withObject("/detailAttribute/sellerCodeInfo").put("sellerBarcode", value.asText());
				case "brand", "manufacturer" -> {
					ObjectNode info = current.withObject("/detailAttribute/naverShoppingSearchInfo");
					info.remove(field + "Id");
					info.put(field + "Name", value.asText());
				}
				case "hostedImages" -> {
					if (!value.isArray() || value.isEmpty() || value.size() > 10)
						throw new IllegalArgumentException("이미지 목록이 유효하지 않습니다.");
					ObjectNode images = mapper.createObjectNode();
					images.withObject("/representativeImage").put("url", value.get(0).asText());
					ArrayNode optional = images.putArray("optionalImages");
					for (int i = 1; i < value.size(); i++)
						optional.addObject().put("url", value.get(i).asText());
					current.set("images", images);
				}
			}
		}
		// OUTOFSTOCK is a derived, read-only API status. Preserve explicit zero inventory; never resume a stopped listing.
		if ("OUTOFSTOCK".equals(current.path("statusType").asText()))
			current.put("statusType", "SALE");
		beforeWrite.run();
		sameAccount(prepared.accountReference());
		JsonNode response = parse(rest.put(path(id), Map.of("originProduct", current)));
		if (response.has("code"))
			throw new IllegalStateException("스마트스토어가 필드 변경을 거절했습니다. 실제 값을 재조회하세요.");
		sameAccount(prepared.accountReference());
	}

	private Map<String, String> values(ObjectNode p, Set<String> fields) {
		var out = new LinkedHashMap<String, String>();
		for (String field : fields) {
			JsonNode node = switch (field) {
				case "name" -> p.path("name");
				case "detailHtml" -> p.path("detailContent");
				case "barcode" -> p.path("detailAttribute").path("sellerCodeInfo").path("sellerBarcode");
				case "brand", "manufacturer" ->
					p.path("detailAttribute").path("naverShoppingSearchInfo").path(field + "Name");
				default -> p.path("images");
			};
			if (field.equals("hostedImages")) {
				String first = node.path("representativeImage").path("url").asText();
				requireUrl(first);
				ArrayNode urls = mapper.createArrayNode().add(first);
				JsonNode optional = node.path("optionalImages");
				if (!optional.isMissingNode() && !optional.isNull() && !optional.isArray())
					throw new IllegalStateException("추가 이미지 목록을 확인할 수 없습니다.");
				for (JsonNode image : optional) {
					String url = image.path("url").asText();
					requireUrl(url);
					urls.add(url);
				}
				out.put(field, urls.toString());
			} else {
				if (!node.isMissingNode() && !node.isNull() && !node.isTextual())
					throw new IllegalStateException("마켓 필드 값 형식을 확인할 수 없습니다: " + field);
				if ((field.equals("name") || field.equals("detailHtml")) && !node.isTextual())
					throw new IllegalStateException("필수 마켓 필드가 없습니다: " + field);
				out.put(field, node.asText(""));
			}
		}
		return out;
	}

	private ObjectNode product(String id, String sb, String account, Set<String> fields) {
		String url = path(id);
		sameAccount(account);
		JsonNode root = parse(rest.get(url));
		JsonNode p = root.path("originProduct");
		if (root.has("code") || !p.isObject() || (p.hasNonNull("id") && !id.equals(p.path("id").asText())) || sb == null
			|| sb.isBlank()
			|| !sb.equals(p.path("detailAttribute").path("sellerCodeInfo").path("sellerManagementCode").asText()))
			throw new IllegalStateException("스마트스토어 상품번호·SB코드가 정확히 일치하지 않습니다.");
		if (fields.contains("name")
			&& !root.path("smartstoreChannelProduct").path("channelProductName").asText("").isBlank())
			throw new UnsupportedOperationException("스마트스토어 채널 전용 상품명이 설정되어 있습니다. 원상품명 변경만으로 표시 상품명의 반영을 확정할 수 없습니다.");
		sameAccount(account);
		return (ObjectNode)p;
	}

	private void requireWritable(ObjectNode p) {
		String state = p.path("statusType").asText();
		if (Set.of("WAIT", "UNADMISSION", "REJECTION", "SUSPENSION", "CLOSE", "PROHIBITION", "DELETE").contains(state))
			throw new UnsupportedOperationException("스마트스토어 판매 상태 " + state + ": 필드 쓰기를 보류합니다.");
		if (!Set.of("SALE", "OUTOFSTOCK").contains(state))
			throw new IllegalStateException("스마트스토어 판매 상태 " + state + ": 필드 쓰기를 보류합니다.");
		if ("OUTOFSTOCK".equals(state)) {
			JsonNode q = p.path("stockQuantity");
			if (!q.isIntegralNumber() || q.longValue() != 0)
				throw new UnsupportedOperationException("일시 품절의 0개 재고를 확인할 수 없어 전체 상품 수정을 보류합니다.");
			JsonNode option = p.path("detailAttribute").path("optionInfo");
			for (String key : List.of("optionCombinations", "optionStandards"))
				for (JsonNode item : option.path(key))
					if (!item.path("stockQuantity").isIntegralNumber() || item.path("stockQuantity").longValue() != 0)
						throw new UnsupportedOperationException("품절 옵션의 수량을 확인할 수 없어 판매 상태 변환을 보류합니다.");
		}
	}

	private String account() {
		String value = rest.accountReference();
		if (value == null || value.isBlank())
			throw new IllegalStateException("스마트스토어 계정을 확인할 수 없습니다.");
		return value;
	}

	private void sameAccount(String expected) {
		if (!Objects.equals(expected, account()))
			throw new IllegalStateException("스마트스토어 계정이 변경되었습니다.");
	}

	private static String path(String id) {
		if (id == null || !id.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("원상품 번호가 유효하지 않습니다.");
		return "/v2/products/origin-products/" + id;
	}

	private static void requireFields(Set<String> fields) {
		if (fields == null || fields.isEmpty() || !FIELDS.containsAll(fields))
			throw new UnsupportedOperationException("스마트스토어에서 검증하지 않은 필드가 포함되어 있습니다.");
	}

	private static void requireUrl(String text) {
		try {
			URI u = URI.create(text);
			if (!Set.of("http", "https").contains(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null)
				throw new IllegalArgumentException();
		} catch (Exception e) {
			throw new IllegalArgumentException("이미지 URL을 확인하세요.");
		}
	}

	private JsonNode parse(String text) {
		try {
			JsonNode n = mapper.readTree(text);
			if (n == null || !n.isObject())
				throw new IllegalStateException("마켓 응답 객체가 없습니다.");
			return n;
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("스마트스토어 응답 해석 실패", e);
		}
	}
}
