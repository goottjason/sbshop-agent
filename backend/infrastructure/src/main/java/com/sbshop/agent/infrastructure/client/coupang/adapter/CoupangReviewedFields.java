package com.sbshop.agent.infrastructure.client.coupang.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import java.net.URI;
import java.util.*;

/** Approval-required deltas over the exact seller product, never a legacy payload repair. */
final class CoupangReviewedFields {
	static final Set<String> FIELDS = Set.of("name", "brand", "manufacturer", "barcode", "hostedImages", "detailHtml");
	private static final String BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
	private final CoupangRestClient rest;
	private final ObjectMapper mapper;

	CoupangReviewedFields(CoupangRestClient rest, ObjectMapper mapper) {
		this.rest = rest;
		this.mapper = mapper;
	}

	PreparedMarketFields prepare(Product product, String id, String option, Set<String> fields) {
		requireFields(fields);
		String account = account();
		ObjectNode current = product(id, option, product.getSbCode(), account);
		requireApproved(current);
		String resolved = item(current).path("vendorItemId").asText();
		requireSelling(resolved, account);
		ObjectNode delta = mapper.createObjectNode();
		for (String field : fields)
			switch (field) {
				case "name" -> {
					String value = product.getProductName();
					if (value == null || value.isBlank() || value.length() > 100)
						throw new IllegalArgumentException("쿠팡 상품명은 비어 있지 않은 100자 이내 값이어야 합니다.");
					delta.putObject(field).put("sellerProductName", value).put("displayProductName", value);
				}
				case "brand" -> delta.put(field, Objects.toString(product.getBrand(), ""));
				case "manufacturer" -> delta.put(field, product.getSourcingInfo() == null ? ""
					: Objects.toString(product.getSourcingInfo().getManufacturer(), ""));
				case "barcode" -> {
					String value = product.getProductSpec() == null ? null : product.getProductSpec().getBarcode();
					if (value == null || value.isBlank())
						throw new UnsupportedOperationException("쿠팡 바코드 삭제는 없음 사유 검토 계약이 필요합니다. 빈 값으로 전송하지 않습니다.");
					delta.put(field, value);
				}
				case "hostedImages" -> {
					// USED_PRODUCT or another gallery type cannot silently disappear during replacement.
					imageValues(item(current).path("images"));
					List<String> images = product.getHostedImages();
					if (images == null || images.isEmpty() || images.size() > 10
						|| new HashSet<>(images).size() != images.size())
						throw new IllegalArgumentException("쿠팡 대표·추가 이미지는 중복 없는 1~10개여야 합니다.");
					ArrayNode values = delta.putArray(field);
					for (int i = 0; i < images.size(); i++) {
						String url = images.get(i);
						requireUrl(url);
						values.addObject().put("imageOrder", i).put("imageType", i == 0 ? "REPRESENTATION" : "DETAIL")
							.put("vendorPath", url);
					}
				}
				case "detailHtml" -> {
					String html = product.getDetailHtml();
					if (html == null || html.isBlank())
						throw new IllegalArgumentException("쿠팡 상세 HTML은 비울 수 없습니다.");
					delta.putArray(field).addObject().put("contentsType", "HTML").putArray("contentDetails").addObject()
						.put("content", html).put("detailType", "TEXT");
				}
				default -> throw new UnsupportedOperationException("쿠팡 미확인 필드");
			}
		Map<String, String> expected = deltaValues(delta);
		ObjectNode payload = mapper.createObjectNode().put("sellerProductItemId",
			item(current).path("sellerProductItemId").asText());
		payload.set("changes", delta);
		sameAccount(account);
		return new PreparedMarketFields(account, resolved, true, expected, payload.toString());
	}

	MarketFieldsRead read(String id, String option, String sb, Set<String> fields) {
		requireFields(fields);
		requireId(option);
		String account = account();
		ObjectNode current = product(id, option, sb, account);
		MarketFieldsRead.Approval approval = approval(current);
		String block = null;
		if (approval != MarketFieldsRead.Approval.APPROVED)
			block = "쿠팡 심사 상태 확인 필요: " + current.path("statusName").asText("미상");
		else if (!selling(option, account))
			block = "쿠팡 옵션 판매가 중지되어 필드 쓰기를 보류합니다. 자동 판매 재개는 하지 않습니다.";
		Map<String, String> values = values(current, fields);
		sameAccount(account);
		return new MarketFieldsRead(values, account, option, approval, block);
	}

	void write(String id, String option, String sb, PreparedMarketFields prepared, Runnable beforeWrite) {
		requireFields(prepared.expectedValues().keySet());
		requireId(option);
		if (!prepared.requiresApproval() || !option.equals(prepared.resolvedOptionId()))
			throw new IllegalArgumentException("쿠팡 심사 요청·옵션 검토값이 다릅니다.");
		sameAccount(prepared.accountReference());
		ObjectNode current = product(id, option, sb, prepared.accountReference());
		requireApproved(current);
		requireSelling(option, prepared.accountReference());
		ObjectNode payload = parse(prepared.payload());
		JsonNode delta = payload.path("changes");
		if (!item(current).path("sellerProductItemId").asText().equals(payload.path("sellerProductItemId").asText())
			|| !delta.isObject()
			|| !prepared.expectedValues().equals(deltaValues((ObjectNode)delta)))
			throw new IllegalArgumentException("쿠팡 옵션·검토한 목표값이 변경되었습니다.");
		if (values(current, prepared.expectedValues().keySet()).equals(prepared.expectedValues()))
			return;
		ObjectNode item = item(current);
		for (String field : prepared.expectedValues().keySet()) {
			JsonNode value = delta.path(field);
			switch (field) {
				case "name" -> {
					current.put("sellerProductName", value.path("sellerProductName").asText());
					current.put("displayProductName", value.path("displayProductName").asText());
				}
				case "brand" -> current.put("brand", value.asText());
				case "manufacturer" -> current.put("manufacture", value.asText());
				case "barcode" -> {
					if (value.asText().isBlank())
						throw new IllegalArgumentException("바코드 없음 사유가 검토되지 않았습니다.");
					item.put("barcode", value.asText());
					item.put("emptyBarcode", false);
					item.putNull("emptyBarcodeReason");
				}
				case "hostedImages" -> {
					imageValues(item.path("images"));
					imageValues(value);
					for (JsonNode image : value)
						requireUrl(requiredText(image.path("vendorPath")));
					item.set("images", value.deepCopy());
				}
				case "detailHtml" -> {
					contentValues(value);
					item.set("contents", value.deepCopy());
				}
			}
		}
		current.put("requested", true);
		// Do not catch this callback: lease/account/DB failures must reach the engine unchanged.
		beforeWrite.run();
		sameAccount(prepared.accountReference());
		JsonNode receipt;
		try {
			receipt = parse(rest.put(BASE, current));
		} catch (RuntimeException e) {
			throw transfer(e);
		}
		successEnvelope(receipt);
		sameAccount(prepared.accountReference());
	}

	private ObjectNode product(String id, String option, String sb, String account) {
		requireId(id);
		if (option != null)
			requireId(option);
		sameAccount(account);
		JsonNode root = get(BASE + "/" + id), p = successEnvelope(root).path("data"), items = p.path("items");
		if (!p.isObject() || !id.equals(p.path("sellerProductId").asText())
			|| !rest.resolveVendorId().equals(p.path("vendorId").asText())
			|| !items.isArray() || items.size() != 1 || !items.get(0).isObject() || sb == null || sb.isBlank()
			|| !sb.equals(items.get(0).path("externalVendorSku").asText()))
			throw new IllegalStateException("쿠팡 계정·등록상품·단일 옵션·SB코드가 정확히 일치하지 않습니다.");
		String resolved = items.get(0).path("vendorItemId").asText();
		requireId(resolved);
		requireId(items.get(0).path("sellerProductItemId").asText());
		if (option != null && !option.equals(resolved))
			throw new IllegalStateException("쿠팡 vendorItemId가 검토 옵션과 다릅니다.");
		sameAccount(account);
		return (ObjectNode)p;
	}

	private boolean selling(String option, String account) {
		sameAccount(account);
		JsonNode data = successEnvelope(
			get("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + option + "/inventories"))
			.path("data");
		if (!option.equals(data.path("sellerItemId").asText()) || !data.path("onSale").isBoolean())
			throw new IllegalStateException("쿠팡 옵션 판매 상태를 확인할 수 없습니다.");
		sameAccount(account);
		return data.path("onSale").booleanValue();
	}

	private void requireSelling(String option, String account) {
		if (!selling(option, account))
			throw new UnsupportedOperationException("쿠팡 판매 중지 옵션은 필드 전송을 보류합니다. 자동 재개하지 않습니다.");
	}

	private void requireApproved(ObjectNode product) {
		if (approval(product) != MarketFieldsRead.Approval.APPROVED)
			throw new UnsupportedOperationException(
				"쿠팡 승인완료 상품만 필드 변경 심사를 요청할 수 있습니다: " + product.path("statusName").asText("미상"));
	}

	private MarketFieldsRead.Approval approval(ObjectNode p) {
		return switch (p.path("statusName").asText()) {
			case "승인완료" -> MarketFieldsRead.Approval.APPROVED;
			case "심사중", "승인대기중" -> MarketFieldsRead.Approval.PENDING;
			case "승인반려" -> MarketFieldsRead.Approval.REJECTED;
			default -> MarketFieldsRead.Approval.UNKNOWN;
		};
	}

	private Map<String, String> values(ObjectNode p, Set<String> fields) {
		Map<String, String> values = new LinkedHashMap<>();
		ObjectNode item = item(p);
		for (String field : fields)
			values.put(field, switch (field) {
				case "name" ->
					mapper.createObjectNode().put("sellerProductName", requiredText(p.path("sellerProductName")))
						.put("displayProductName", optionalText(p.path("displayProductName"))).toString();
				case "brand" -> optionalText(p.path("brand"));
				case "manufacturer" -> optionalText(p.path("manufacture"));
				case "barcode" -> barcodeValue(item);
				case "hostedImages" -> imageValues(item.path("images")).toString();
				case "detailHtml" -> contentValues(item.path("contents")).toString();
				default -> throw new UnsupportedOperationException("쿠팡 미확인 필드");
			});
		return values;
	}

	private String barcodeValue(ObjectNode item) {
		if (!item.path("emptyBarcode").isBoolean())
			throw new IllegalStateException("쿠팡 바코드 사용 여부를 확인할 수 없습니다.");
		return item.path("emptyBarcode").booleanValue() ? "" : optionalText(item.path("barcode"));
	}

	private Map<String, String> deltaValues(ObjectNode delta) {
		Set<String> keys = new HashSet<>();
		delta.fieldNames().forEachRemaining(keys::add);
		requireFields(keys);
		Map<String, String> values = new LinkedHashMap<>();
		for (String key : keys) {
			JsonNode node = delta.path(key);
			String canonical;
			if (key.equals("hostedImages"))
				canonical = imageValues(node).toString();
			else if (key.equals("detailHtml"))
				canonical = contentValues(node).toString();
			else if (key.equals("name"))
				canonical = mapper.createObjectNode()
					.put("sellerProductName", requiredText(node.path("sellerProductName")))
					.put("displayProductName", requiredText(node.path("displayProductName"))).toString();
			else
				canonical = requiredText(node);
			values.put(key, canonical);
		}
		return values;
	}

	private ArrayNode imageValues(JsonNode images) {
		if (!images.isArray() || images.isEmpty() || images.size() > 10)
			throw new UnsupportedOperationException("쿠팡 대표·추가 이미지 전체를 확인할 수 없습니다.");
		TreeMap<Integer, JsonNode> ordered = new TreeMap<>();
		for (JsonNode image : images) {
			JsonNode order = image.path("imageOrder");
			if (!order.isIntegralNumber() || !order.canConvertToInt() || order.intValue() < 0
				|| ordered.put(order.intValue(), image) != null)
				throw new IllegalStateException("쿠팡 이미지 순서를 확인할 수 없습니다.");
		}
		ArrayNode out = mapper.createArrayNode();
		int i = 0;
		for (var entry : ordered.entrySet()) {
			JsonNode image = entry.getValue();
			String type = image.path("imageType").asText();
			if (entry.getKey() != i || !Objects.equals(type, i == 0 ? "REPRESENTATION" : "DETAIL"))
				throw new UnsupportedOperationException("쿠팡 중고 이미지·미확인 순서의 갤러리는 자동 대체하지 않습니다.");
			ObjectNode value = out.addObject().put("imageOrder", i++).put("imageType", type);
			String vendor = optionalText(image.path("vendorPath"));
			if (!vendor.isBlank()) {
				value.put("vendorPath", vendor);
			} else {
				String cdn = requiredText(image.path("cdnPath"));
				if (cdn.isBlank())
					throw new IllegalStateException("쿠팡 이미지 경로가 없습니다.");
				value.put("cdnPath", cdn);
			}
		}
		return out;
	}

	private ArrayNode contentValues(JsonNode contents) {
		if (!contents.isArray() || contents.isEmpty())
			throw new IllegalStateException("쿠팡 상세 컨텐츠 전체가 없습니다.");
		ArrayNode out = mapper.createArrayNode();
		for (JsonNode content : contents) {
			String type = requiredText(content.path("contentsType"));
			JsonNode details = content.path("contentDetails");
			if (!details.isArray() || details.isEmpty())
				throw new IllegalStateException("쿠팡 상세 컨텐츠 형식을 확인할 수 없습니다.");
			ArrayNode copied = out.addObject().put("contentsType", type).putArray("contentDetails");
			for (JsonNode detail : details)
				copied.addObject().put("content", requiredText(detail.path("content"))).put("detailType",
					requiredText(detail.path("detailType")));
		}
		return out;
	}

	private JsonNode get(String path) {
		try {
			return parse(rest.get(path));
		} catch (RuntimeException e) {
			throw transfer(e);
		}
	}

	private RuntimeException transfer(RuntimeException e) {
		return e instanceof MarketTransferFailure ? e : MarketApiEvidence.transferFailure(e);
	}

	private JsonNode successEnvelope(JsonNode root) {
		JsonNode envelope = root;
		if ("200".equals(root.path("code").asText()) && root.path("data").isObject())
			envelope = root.path("data");
		if ("ERROR".equals(envelope.path("code").asText()))
			throw new MarketTransferFailure("HTTP_400",
				"쿠팡이 필드 요청을 명시 거절했습니다. " + com.sbshop.agent.core.application.product.ProductMarketSyncService
					.sanitizeMarketMessage(envelope.path("message").asText("사유 미제공")),
				null, null);
		if (!"SUCCESS".equals(envelope.path("code").asText()))
			throw new IllegalStateException("쿠팡 응답에 성공 접수 확인이 없습니다.");
		return envelope;
	}

	private ObjectNode parse(String text) {
		try {
			JsonNode node = mapper.readTree(text);
			if (node == null || !node.isObject())
				throw new IllegalStateException("쿠팡 응답 객체가 없습니다.");
			return (ObjectNode)node;
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("쿠팡 응답 해석 실패", e);
		}
	}

	private ObjectNode item(ObjectNode p) {
		return (ObjectNode)p.path("items").get(0);
	}

	private String account() {
		String account = MarketApiEvidence.account("COUPANG", rest.resolveVendorId());
		if (account == null)
			throw new IllegalStateException("쿠팡 계정을 확인할 수 없습니다.");
		return account;
	}

	private void sameAccount(String expected) {
		if (!Objects.equals(expected, account()))
			throw new UnsupportedOperationException("쿠팡 연동 계정이 변경되었습니다.");
	}

	private static void requireFields(Set<String> fields) {
		if (fields == null || fields.isEmpty() || !FIELDS.containsAll(fields))
			throw new UnsupportedOperationException("쿠팡에서 확인하지 않은 필드가 포함되어 있습니다.");
	}

	private static void requireId(String id) {
		if (id == null || !id.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("쿠팡 상품·옵션 번호를 확인하세요.");
	}

	private static String optionalText(JsonNode node) {
		if (node.isMissingNode() || node.isNull())
			return "";
		return requiredText(node);
	}

	private static String requiredText(JsonNode node) {
		if (!node.isTextual())
			throw new IllegalStateException("쿠팡 필드 문자열을 확인할 수 없습니다.");
		return node.textValue();
	}

	private static void requireUrl(String url) {
		try {
			URI uri = URI.create(url);
			if (url.length() > 200 || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
				|| uri.getUserInfo() != null || uri.getFragment() != null
				|| !Set.of(-1, 80, 443).contains(uri.getPort()))
				throw new IllegalArgumentException();
		} catch (Exception e) {
			throw new IllegalArgumentException("쿠팡 이미지 URL은 200자 이내의 HTTP(S) 80·443 포트 경로여야 합니다.");
		}
	}
}
