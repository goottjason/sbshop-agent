package com.sbshop.agent.infrastructure.client.coupang.adapter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sbshop.agent.core.config.MarketRegistrationDefaults;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload;
import java.math.*;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Frozen, explicitly reviewed creation. Neither a POST receipt nor a pending approval creates a confirmed link. */
@Component
@RequiredArgsConstructor
public class CoupangReviewedPublication {
	static final String BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
	private final CoupangRestClient rest;
	private final ObjectMapper mapper;
	private final MarketRegistrationDefaults defaults;

	PreparedMarketPublication prepare(Product product, BigDecimal price) {
		return prepare(product, price, MarketPublishContext.empty());
	}

	PreparedMarketPublication prepare(Product product, BigDecimal price, MarketPublishContext context) {
		context = normalize(context);
		String account = account(), vendor = vendor();
		if (product.getSbCode() == null || product.getSbCode().isBlank())
			throw new IllegalArgumentException("쿠팡 등록에는 SB코드가 필요합니다.");
		String name = product.getProductName();
		if (name == null || name.isBlank() || name.length() > 100)
			throw new IllegalArgumentException("쿠팡 상품명은 100자 이내로 검토하세요.");
		if (product.getBrand() == null || product.getBrand().isBlank())
			throw new UnsupportedOperationException("쿠팡 등록 브랜드를 확인하세요.");
		int sale = price == null ? 0 : price.intValueExact();
		if (sale <= 0 || sale % 100 != 0)
			throw new IllegalArgumentException("쿠팡 검토 판매가는 양수의 100원 단위여야 합니다.");
		if (product.getSalesQuantity() < 0 || product.getSalesQuantity() > 999999 || product.getStockStatus() == null)
			throw new IllegalArgumentException("판매용 수량·소싱 재고 상태를 확인하세요.");
		int quantity = product.getStockStatus() == StockStatus.OUT_OF_STOCK ? 0 : product.getSalesQuantity();
		Integer bundle = product.getLogisticsInfo() == null ? null : product.getLogisticsInfo().getBundleQuantity();
		if (bundle == null || bundle < 1)
			throw new IllegalArgumentException("묶음수량을 확인하세요.");
		String html = product.getDetailHtml();
		if (html == null || html.isBlank())
			throw new IllegalArgumentException("상품 상세 HTML이 필요합니다.");
		var images = product.getHostedImages();
		if (images == null || images.isEmpty() || images.size() > 10 || new HashSet<>(images).size() != images.size())
			throw new IllegalArgumentException("대표·추가 이미지 1~10개를 확인하세요.");
		List<CoupangProductPayload.Item.Image> gallery = new ArrayList<>();
		for (int i = 0; i < images.size(); i++) {
			url(images.get(i));
			gallery.add(new CoupangProductPayload.Item.Image(i, i == 0 ? "REPRESENTATION" : "DETAIL", images.get(i)));
		}
		var descriptor = category(product, context.categoryId());
		String category = descriptor.id(), categoryName = descriptor.name();
		JsonNode meta = descriptor.meta();
		var attributes = attributes(meta, product, context);
		var notices = notices(meta, product, context);
		var documents = documents(meta, context);
		var certificates = certificates(meta, context);
		var shipping = shipping(vendor);
		ObjectNode body = mapper.valueToTree(CoupangProductPayload.create(product, Long.valueOf(category), name,
			Objects.toString(product.getBaseName(), name), product.getBrand(), sale, List.of(), gallery, notices,
			attributes, html, shipping));
		ObjectNode item = (ObjectNode)body.path("items").get(0);
		item.put("salePrice", sale).put("originalPrice", sale).put("maximumBuyCount", quantity).put("unitCount",
			bundle);
		if (item.path("emptyBarcode").asBoolean())
			item.put("emptyBarcodeReason", "시스템 상품에 바코드 정보가 등록되어 있지 않습니다.");
		// Manufacture is a top-level documented field, not the legacy nested Item member.
		item.remove("manufacture");
		if (product.getSourcingInfo() != null && product.getSourcingInfo().getManufacturer() != null)
			body.put("manufacture", product.getSourcingInfo().getManufacturer());
		body.put("requested", true);
		body.set("requiredDocuments", documents);
		item.set("certifications", certificates);
		sameAccount(account);
		return new PreparedMarketPublication(body.toString(), account, name, category, categoryName, price, quantity,
			images.getFirst(),
			Map.of("배송", "해외구매대행 · 무료배송", "출고지 코드", shipping.outboundShippingPlaceCode().toString(),
				"반품지", shipping.returnChargeName() + " (" + shipping.returnCenterCode() + ")", "반품 주소",
				shipping.returnAddress() + " " + shipping.returnAddressDetail(),
				"반품비", shipping.returnCharge() + "원", "심사 요청", "등록 후 판매 승인 심사 요청"));
	}

	Map<String, String> submit(Product product, String operationId, String payload, Runnable beforeWrite) {
		UUID.fromString(operationId);
		ObjectNode body = parse(payload);
		String account = account();
		validateFrozen(body, product.getSbCode());
		beforeWrite.run();
		sameAccount(account);
		validateFrozen(body, product.getSbCode());
		JsonNode data = success(request("POST", BASE, body)).path("data");
		String id = data.asText();
		id(id);
		sameAccount(account);
		return Map.of("sellerProductId", id);
	}

	VerifiedMarketPublication read(String listingId, String sb, String payload) {
		id(listingId);
		ObjectNode expected = parse(payload);
		String account = account();
		validateFrozen(expected, sb);
		JsonNode actual = success(get(BASE + "/" + listingId)).path("data"), items = actual.path("items");
		if (!actual.isObject() || !listingId.equals(actual.path("sellerProductId").asText())
			|| !vendor().equals(actual.path("vendorId").asText())
			|| !items.isArray() || items.size() != 1 || !sb.equals(items.get(0).path("externalVendorSku").asText()))
			throw new IllegalStateException("쿠팡 등록 조회의 계정·상품·단일 옵션·SB코드가 다릅니다.");
		sameAccount(account);
		String status = actual.path("statusName").asText();
		if (Set.of("심사중", "승인대기중").contains(status))
			return new VerifiedMarketPublication(false, Map.of("sellerProductId", listingId),
				"쿠팡 심사가 진행 중입니다. 등록 확정 전이며 재전송하지 않습니다.", true);
		if (!"승인완료".equals(status))
			return new VerifiedMarketPublication(false, Map.of("sellerProductId", listingId),
				"쿠팡 심사 상태: " + status + ". 승인완료 확인이 필요합니다.", false);
		JsonNode item = items.get(0), planned = expected.path("items").get(0);
		String option = item.path("vendorItemId").asText();
		id(option);
		id(item.path("sellerProductItemId").asText());
		Map<String, String> ids = new LinkedHashMap<>();
		ids.put("sellerProductId", listingId);
		ids.put("vendorItemId", option);
		for (String key : List.of("sellerProductName", "displayProductName", "displayCategoryCode", "brand",
			"manufacture", "vendorId", "deliveryMethod", "deliveryChargeType", "deliveryCharge", "returnCenterCode",
			"returnCharge", "outboundShippingPlaceCode", "requiredDocuments"))
			if (expected.hasNonNull(key) && !sameValue(expected.get(key), actual.path(key)))
				return mismatch(ids, key);
		for (String key : List.of("externalVendorSku", "unitCount", "barcode", "emptyBarcode", "notices", "attributes",
			"contents", "certifications"))
			if (expected.path("items").get(0).hasNonNull(key) && !sameValue(planned.path(key), item.path(key)))
				return mismatch(ids, key);
		if (!sameGallery(planned.path("images"), item.path("images")))
			return mismatch(ids, "images");
		JsonNode stock = success(
			get("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + option + "/inventories"))
			.path("data");
		if (!option.equals(stock.path("sellerItemId").asText())
			|| !sameValue(planned.path("salePrice"), stock.path("salePrice"))
			|| !sameValue(planned.path("maximumBuyCount"), stock.path("amountInStock"))
			|| !stock.path("onSale").isBoolean()
			|| planned.path("maximumBuyCount").intValue() > 0 && !stock.path("onSale").booleanValue())
			return mismatch(ids, "판매가·판매용 수량·판매 상태");
		sameAccount(account);
		return new VerifiedMarketPublication(true, ids, "쿠팡 별도 조회에서 계정·SB코드·승인완료·검토 필드·옵션 가격과 수량을 확인했습니다.", false);
	}

	private List<CoupangProductPayload.Item.Attribute> attributes(JsonNode meta, Product p,
		MarketPublishContext context) {
		Map<String, String> supplied = strings(context.extraFields().get("attributes"));
		JsonNode raw = meta.path("attributes");
		if (!raw.isArray())
			throw new IllegalStateException("필수 구매옵션 목록이 없습니다.");
		Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
		List<JsonNode> singles = new ArrayList<>();
		for (String name : supplied.keySet())
			if (java.util.stream.StreamSupport.stream(raw.spliterator(), false)
				.noneMatch(a -> name.equals(a.path("attributeTypeName").asText())))
				throw new IllegalArgumentException("쿠팡 메타에 없는 옵션입니다: " + name);
		for (JsonNode attr : raw)
			if ("MANDATORY".equals(attr.path("required").asText())
				|| supplied.containsKey(attr.path("attributeTypeName").asText())) {
				String group = attr.path("groupNumber").asText();
				if ("EXPOSED".equals(attr.path("exposed").asText()) && !group.isBlank() && !"NONE".equals(group))
					groups.computeIfAbsent(group, k -> new ArrayList<>()).add(attr);
				else
					singles.add(attr);
			}
		for (var group : groups.values()) {
			var candidates = group.stream().filter(a -> attributeValue(a, p, supplied) != null).toList();
			if (candidates.size() != 1)
				throw new UnsupportedOperationException("쿠팡 필수 옵션 그룹의 실제 값을 하나로 확인할 수 없습니다: "
					+ group.stream().map(a -> a.path("attributeTypeName").asText()).toList());
			singles.add(candidates.getFirst());
		}
		List<CoupangProductPayload.Item.Attribute> out = new ArrayList<>();
		for (JsonNode attr : singles) {
			String value = attributeValue(attr, p, supplied);
			if (value == null)
				throw new UnsupportedOperationException(
					"쿠팡 필수 옵션의 실제 값 확인 필요: " + attr.path("attributeTypeName").asText());
			out.add(new CoupangProductPayload.Item.Attribute(text(attr.path("attributeTypeName")), value,
				text(attr.path("exposed"))));
		}
		return out;
	}

	private String attributeValue(JsonNode attr, Product p, Map<String, String> supplied) {
		String entered = supplied.get(attr.path("attributeTypeName").asText());
		if (entered != null && !entered.isBlank())
			return validateAttributeValue(attr, entered);
		String type = attr.path("attributeTypeName").asText(), value = null, unit = null;
		BigDecimal amount = null;
		int bundle = p.getLogisticsInfo().getBundleQuantity();
		if (Set.of("수량", "묶음 수량").contains(type)) {
			amount = BigDecimal.valueOf(bundle);
			unit = "개";
		} else if (Set.of("개당 용량", "개당 중량", "개당 캡슐/정", "용량", "중량", "총 용량", "총 중량").contains(type)
			&& p.getProductSpec() != null && p.getProductSpec().getCapacity() != null
			&& p.getProductSpec().getMeasureUnit() != null) {
			amount = p.getProductSpec().getCapacity();
			unit = measure(p.getProductSpec().getMeasureUnit());
			if (type.startsWith("총 "))
				amount = amount.multiply(BigDecimal.valueOf(bundle));
		}
		if (amount != null && amount.signum() > 0 && unit != null && attr.path("usableUnits").isArray()) {
			String chosen = null;
			for (JsonNode allowed : attr.path("usableUnits"))
				if (allowed.isTextual() && allowed.textValue().equalsIgnoreCase(unit))
					chosen = allowed.textValue();
			if (chosen != null)
				value = amount.stripTrailingZeros().toPlainString() + chosen;
		}
		if (value == null)
			return null;
		if ("SELECT".equals(attr.path("inputType").asText())) {
			for (JsonNode allowed : attr.path("inputValues"))
				if (value.equals(allowed.asText()))
					return value;
			return null;
		}
		if (attr.hasNonNull("inputType") && !"INPUT".equals(attr.path("inputType").asText()))
			return null;
		return "NUMBER".equals(attr.path("dataType").asText()) ? value : null;
	}

	private static String measure(MeasureUnit u) {
		return switch (u) {
			case MG -> "mg";
			case G -> "g";
			case KG -> "kg";
			case ML -> "ml";
			case L -> "L";
			case OZ -> "oz";
			case LB -> "lb";
			case TABLET -> "정";
			case CAPSULE -> "캡슐";
			default -> null;
		};
	}

	private String validateAttributeValue(JsonNode attr, String value) {
		if (value.length() > 30)
			throw new IllegalArgumentException("쿠팡 옵션값은 30자 이내여야 합니다: " + attr.path("attributeTypeName").asText());
		if ("SELECT".equals(attr.path("inputType").asText())) {
			for (JsonNode allowed : attr.path("inputValues"))
				if (value.equals(allowed.asText()))
					return value;
			throw new IllegalArgumentException("쿠팡 허용 선택값과 다릅니다: " + attr.path("attributeTypeName").asText());
		}
		if ("NUMBER".equals(attr.path("dataType").asText())) {
			var matcher = java.util.regex.Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(.*)$").matcher(value);
			if (!matcher.matches() || new BigDecimal(matcher.group(1)).signum() <= 0)
				throw new IllegalArgumentException("쿠팡 숫자 옵션을 확인하세요.");
			String unit = matcher.group(2);
			boolean allowed = attr.path("usableUnits").isArray() && attr.path("usableUnits").isEmpty()
				&& unit.isEmpty();
			for (JsonNode option : attr.path("usableUnits"))
				if (unit.equals(option.asText()))
					allowed = true;
			if (!allowed)
				throw new IllegalArgumentException("쿠팡 옵션의 허용 단위를 확인하세요: " + attr.path("attributeTypeName").asText());
		} else if ("DATE".equals(attr.path("dataType").asText())) {
			try {
				java.time.LocalDate.parse(value);
			} catch (Exception e) {
				throw new IllegalArgumentException("쿠팡 날짜 옵션을 확인하세요.");
			}
		} else if (!"STRING".equals(attr.path("dataType").asText()))
			throw new IllegalArgumentException("쿠팡 옵션 형식을 확인하세요.");
		return value;
	}

	private List<CoupangProductPayload.Item.Notice> notices(JsonNode meta, Product p, MarketPublishContext context) {
		JsonNode categories = meta.path("noticeCategories");
		String selected = context.extraString("noticeCategoryName");
		if (selected == null && categories.isArray() && categories.size() == 1)
			selected = categories.get(0).path("noticeCategoryName").asText();
		JsonNode category = one(categories, "noticeCategoryName", selected),
			details = category.path("noticeCategoryDetailNames");
		if (!details.isArray())
			throw new IllegalStateException("쿠팡 필수 고시 목록이 없습니다.");
		for (String supplied : context.noticeFields().keySet())
			if (java.util.stream.StreamSupport.stream(details.spliterator(), false)
				.noneMatch(d -> supplied.equals(d.path("noticeCategoryDetailName").asText())))
				throw new IllegalArgumentException("선택한 고시 분류에 없는 항목입니다: " + supplied);
		List<CoupangProductPayload.Item.Notice> out = new ArrayList<>();
		for (JsonNode detail : details)
			if ("MANDATORY".equals(detail.path("required").asText())
				|| context.noticeFields().containsKey(detail.path("noticeCategoryDetailName").asText())) {
				String field = text(detail.path("noticeCategoryDetailName"));
				String value = context.noticeFields().get(field);
				if (value == null)
					value = noticeValue(field, p);
				if (value == null || value.isBlank())
					throw new UnsupportedOperationException("쿠팡 필수 상품고시 실제 내용 확인 필요: " + field);
				if (value.length() > 4000)
					throw new IllegalArgumentException("쿠팡 고시 항목은 4000자 이내로 검토하세요.");
				out.add(new CoupangProductPayload.Item.Notice(text(category.path("noticeCategoryName")), field, value));
			}
		return out;
	}

	private String noticeValue(String field, Product p) {
		return switch (field) {
			case "제품명", "품명", "품명 및 모델명", "품목 또는 명칭" -> p.getProductName();
			case "제조국", "제조국(원산지)", "원산지" -> p.getSourcingInfo() == null ? null : p.getSourcingInfo().getOrigin();
			case "제조사", "제조자" -> p.getSourcingInfo() == null ? null : p.getSourcingInfo().getManufacturer();
			default -> null;
		};
	}

	private ArrayNode documents(JsonNode meta, MarketPublishContext context) {
		JsonNode rows = meta.path("requiredDocumentNames");
		if (!rows.isArray())
			throw new IllegalStateException("쿠팡 필수 서류 목록이 없습니다.");
		Map<String, String> supplied = strings(context.extraFields().get("documentUrls")),
			omitted = strings(context.extraFields().get("documentNotApplicable"));
		ArrayNode out = mapper.createArrayNode();
		for (String name : supplied.keySet())
			one(rows, "templateName", name);
		for (String name : omitted.keySet()) {
			JsonNode row = one(rows, "templateName", name);
			if (!conditionalDocument(row.path("required").asText()))
				throw new IllegalArgumentException("이 서류는 비해당으로 생략할 수 없습니다: " + name);
		}
		for (JsonNode row : rows) {
			String name = text(row.path("templateName")), requirement = row.path("required").asText(),
				url = supplied.get(name);
			boolean required = requirement.startsWith("MANDATORY")
				&& !"MANDATORY_PARALLEL_IMPORTED".equals(requirement);
			if (conditionalDocument(requirement) && "true".equals(omitted.get(name)))
				required = false;
			if (url == null || url.isBlank()) {
				if (required)
					throw new UnsupportedOperationException("쿠팡 필수 서류를 입력하거나 해당 조건을 확인하세요: " + name);
				continue;
			}
			url(url);
			if (url.length() > 150 || !URI.create(url).getPath().toLowerCase(Locale.ROOT)
				.matches(".*\\.(pdf|hwp|doc|docx|txt|png|jpg|jpeg)$"))
				throw new IllegalArgumentException("구비서류 URL은 150자 이내, 허용 파일 형식(5MB 이하)이어야 합니다.");
			out.addObject().put("templateName", name).put("vendorDocumentPath", url);
		}
		return out;
	}

	private boolean conditionalDocument(String requirement) {
		return Set.of("MANDATORY_BATTERY_UN_TEST", "MANDATORY_BATTERY_MSDS_TEST").contains(requirement);
	}

	private ArrayNode certificates(JsonNode meta, MarketPublishContext context) {
		JsonNode rows = meta.path("certifications");
		if (!rows.isArray())
			throw new IllegalStateException("쿠팡 인증 목록이 없습니다.");
		Map<String, String> supplied = strings(context.extraFields().get("certifications"));
		ArrayNode out = mapper.createArrayNode();
		for (String type : supplied.keySet())
			one(rows, "certificationType", type);
		for (JsonNode row : rows) {
			String type = text(row.path("certificationType"));
			String value = supplied.get(type);
			boolean required = "MANDATORY".equals(row.path("required").asText());
			if (value == null) {
				if (required)
					throw new UnsupportedOperationException("쿠팡 필수 인증 입력 필요: " + row.path("name").asText());
				continue;
			}
			if ("CODE".equals(row.path("dataType").asText()) && value.isBlank())
				throw new IllegalArgumentException("쿠팡 인증 코드가 필요합니다: " + type);
			out.addObject().put("certificationType", type).put("certificationCode", value);
		}
		// No default declaration that an arbitrary product is exempt from certification.
		if (out.isEmpty())
			throw new UnsupportedOperationException("쿠팡 인증 구분을 명시 선택하세요. 인증대상 아님도 직접 확인해야 합니다.");
		boolean newAllowed = false;
		for (JsonNode value : meta.path("allowedOfferConditions"))
			if ("NEW".equals(value.asText()))
				newAllowed = true;
		if (!newAllowed)
			throw new UnsupportedOperationException("쿠팡 새 상품 등록 허용 상태를 확인할 수 없습니다.");
		return out;
	}

	private record Category(String id, String name, JsonNode meta) {
	}

	private Category category(Product product, String selected) {
		String category = selected, name;
		if (category == null || category.isBlank()) {
			JsonNode recommendation = success(
				request("POST", "/v2/providers/openapi/apis/api/v1/categorization/predict",
					Map.of("productName", product.getProductName(), "brand", Objects.toString(product.getBrand(), ""))))
				.path("data");
			category = recommendation.path("predictedCategoryId").asText();
			name = text(recommendation.path("predictedCategoryName"));
		} else
			name = "선택한 카테고리 " + selected;
		id(category);
		JsonNode meta = success(
			get("/v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/display-category-codes/"
				+ category))
			.path("data");
		if (!meta.isObject())
			throw new IllegalStateException("쿠팡 카테고리 메타정보를 확인할 수 없습니다.");
		return new Category(category, name, meta);
	}

	Map<String, Object> describe(Product product, String selected) {
		String account = account();
		Category category = category(product, selected);
		JsonNode meta = category.meta();
		List<Map<String, Object>> notices = new ArrayList<>(), attributes = new ArrayList<>(),
			documents = new ArrayList<>(), certificates = new ArrayList<>();
		for (JsonNode row : meta.path("noticeCategories")) {
			List<Map<String, Object>> fields = new ArrayList<>();
			for (JsonNode field : row.path("noticeCategoryDetailNames"))
				fields.add(Map.of("name", field.path("noticeCategoryDetailName").asText(), "required",
					"MANDATORY".equals(field.path("required").asText())));
			notices.add(Map.of("name", row.path("noticeCategoryName").asText(), "fields", fields));
		}
		Map<String, String> suggestedAttributes = new LinkedHashMap<>();
		for (JsonNode row : meta.path("attributes")) {
			attributes.add(Map.of("name", row.path("attributeTypeName").asText(), "required",
				"MANDATORY".equals(row.path("required").asText()), "group", row.path("groupNumber").asText("NONE"),
				"inputType", row.path("inputType").asText("INPUT"), "dataType", row.path("dataType").asText(), "units",
				list(row.path("usableUnits")), "values", list(row.path("inputValues")), "exclusiveGroup",
				"EXPOSED".equals(row.path("exposed").asText())
					&& !Set.of("", "NONE").contains(row.path("groupNumber").asText())));
			String value = attributeValue(row, product, Map.of());
			if (value != null && "MANDATORY".equals(row.path("required").asText()))
				suggestedAttributes.put(row.path("attributeTypeName").asText(), value);
		}
		for (JsonNode row : meta.path("requiredDocumentNames"))
			documents
				.add(Map.of("name", row.path("templateName").asText(), "requirement", row.path("required").asText(),
					"canDeclareNotApplicable", conditionalDocument(row.path("required").asText())));
		for (JsonNode row : meta.path("certifications"))
			certificates.add(
				Map.of("type", row.path("certificationType").asText(), "name", row.path("name").asText(), "required",
					"MANDATORY".equals(row.path("required").asText()), "dataType", row.path("dataType").asText()));
		MarketPublishContext suggestion = new MarketPublishContext(category.id(), category.name(), null, List.of(),
			Map.of(), Map.of("attributes", suggestedAttributes));
		sameAccount(account);
		return Map.of("categoryId", category.id(), "categoryName", category.name(), "noticeCategories", notices,
			"attributes", attributes, "documents", documents, "certifications", certificates, "suggestedContext",
			suggestion);
	}

	Optional<MarketPublishContext> previous(Product product, String category, String raw) {
		if (raw == null || raw.length() > 2_500_000)
			return Optional.empty();
		try {
			JsonNode p = parse(raw);
			if (p.path("data").isObject())
				p = p.path("data");
			JsonNode items = p.path("items");
			if (category == null) {
				category = p.path("displayCategoryCode").asText();
				id(category);
			}
			if (!vendor().equals(p.path("vendorId").asText())
				|| !Objects.equals(category, p.path("displayCategoryCode").asText()) || !items.isArray()
				|| items.size() != 1 || !product.getSbCode().equals(items.get(0).path("externalVendorSku").asText()))
				return Optional.empty();
			JsonNode item = items.get(0);
			Map<String, String> notices = new LinkedHashMap<>(), attrs = new LinkedHashMap<>(),
				docs = new LinkedHashMap<>(), certs = new LinkedHashMap<>();
			Set<String> categories = new HashSet<>();
			for (JsonNode row : item.path("notices")) {
				categories.add(text(row.path("noticeCategoryName")));
				if (notices.put(text(row.path("noticeCategoryDetailName")), text(row.path("content"))) != null)
					return Optional.empty();
			}
			if (categories.size() != 1)
				return Optional.empty();
			for (JsonNode row : item.path("attributes"))
				if (attrs.put(text(row.path("attributeTypeName")), text(row.path("attributeValueName"))) != null)
					return Optional.empty();
			for (JsonNode row : item.path("certifications")) {
				if (!row.path("certificationCode").isTextual())
					return Optional.empty();
				certs.put(text(row.path("certificationType")), row.path("certificationCode").textValue());
			}
			for (JsonNode row : p.path("requiredDocuments")) {
				String path = row.path("vendorDocumentPath").asText();
				if (path.startsWith("https://") || path.startsWith("http://"))
					docs.put(text(row.path("templateName")), path);
			}
			return Optional.of(new MarketPublishContext(category, "같은 카테고리의 과거 등록 자료", null, List.of(), notices,
				Map.of("noticeCategoryName", categories.iterator().next(), "attributes", attrs, "documentUrls", docs,
					"certifications", certs)));
		} catch (RuntimeException invalid) {
			return Optional.empty();
		}
	}

	private List<String> list(JsonNode rows) {
		List<String> out = new ArrayList<>();
		for (JsonNode row : rows)
			if (row.isTextual())
				out.add(row.textValue());
		return out;
	}

	private MarketPublishContext normalize(MarketPublishContext c) {
		return c == null ? MarketPublishContext.empty()
			: new MarketPublishContext(c.categoryId(), c.categoryPath(), c.salePrice(),
				c.keywords() == null ? List.of() : c.keywords(), c.noticeFields() == null ? Map.of() : c.noticeFields(),
				c.extraFields() == null ? Map.of() : c.extraFields());
	}

	private Map<String, String> strings(Object input) {
		if (input == null)
			return Map.of();
		if (!(input instanceof Map<?, ?> map))
			throw new IllegalArgumentException("등록 검토 값은 이름/값 객체여야 합니다.");
		Map<String, String> out = new LinkedHashMap<>();
		for (var entry : map.entrySet()) {
			if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value))
				throw new IllegalArgumentException("등록 검토 값은 문자열이어야 합니다.");
			out.put(key, value);
		}
		return out;
	}

	private CoupangProductPayload.ShippingAccount shipping(String vendor) {
		var existing = CoupangProductPayload.ShippingAccount.legacyDefaults();
		if (!vendor.equals(existing.vendorId()))
			throw new UnsupportedOperationException("쿠팡 등록 배송 설정의 계정 확인이 필요합니다.");
		String outbound = defaults.getCoupangOutboundShippingPlaceCode(),
			returns = defaults.getCoupangReturnCenterCode();
		id(outbound);
		id(returns);
		JsonNode outboundRows = get(
			"/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound?placeCodes=" + outbound)
			.path("content");
		JsonNode place = one(outboundRows, "outboundShippingPlaceCode", outbound);
		if (!place.path("usable").isBoolean() || !place.path("usable").booleanValue())
			throw new UnsupportedOperationException("쿠팡 출고지 사용 가능 여부를 확인하세요.");
		boolean overseas = false;
		for (JsonNode address : place.path("placeAddresses"))
			if ("OVERSEA".equals(address.path("addressType").asText())
				&& !Set.of("", "KR").contains(address.path("countryCode").asText()))
				overseas = true;
		if (!overseas)
			throw new UnsupportedOperationException("쿠팡 구매대행 해외 출고지 주소를 확인하세요.");
		JsonNode response = get(
			"/v2/providers/openapi/apis/api/v5/vendors/" + vendor + "/returnShippingCenters?pageNum=1&pageSize=50");
		if (!"200".equals(response.path("code").asText()) || !"SUCCESS".equals(response.path("message").asText()))
			throw new IllegalStateException("쿠팡 반품지 조회 성공을 확인할 수 없습니다.");
		JsonNode center = one(response.path("data").path("content"), "returnCenterCode", returns);
		if (!vendor.equals(center.path("vendorId").asText()) || !center.path("usable").isBoolean()
			|| !center.path("usable").booleanValue())
			throw new UnsupportedOperationException("쿠팡 반품지 계정·사용 상태를 확인하세요.");
		List<JsonNode> addresses = new ArrayList<>();
		for (JsonNode address : center.path("placeAddresses"))
			if ("KR".equals(address.path("countryCode").asText()))
				addresses.add(address);
		if (addresses.size() != 1)
			throw new UnsupportedOperationException("쿠팡 반품지 주소를 하나로 확인해야 합니다.");
		JsonNode address = addresses.getFirst();
		Integer fee = defaults.getCoupangDeliveryChargeOnReturn();
		if (fee == null || fee < 0)
			throw new IllegalArgumentException("쿠팡 반품비 설정이 필요합니다.");
		return new CoupangProductPayload.ShippingAccount(vendor, existing.vendorUserId(), Integer.valueOf(outbound),
			returns,
			text(center.path("shippingPlaceName")), text(address.path("companyContactNumber")),
			text(address.path("returnZipCode")),
			text(address.path("returnAddress")), text(address.path("returnAddressDetail")), fee);
	}

	private JsonNode one(JsonNode rows, String key, String expected) {
		if (!rows.isArray())
			throw new IllegalStateException("쿠팡 계정 자원 목록이 없습니다.");
		List<JsonNode> matches = new ArrayList<>();
		for (JsonNode row : rows)
			if (Objects.equals(expected, row.path(key).asText()))
				matches.add(row);
		if (matches.size() != 1)
			throw new UnsupportedOperationException("쿠팡 계정 자원 코드가 하나로 확인되지 않습니다: " + key);
		return matches.getFirst();
	}

	private void validateFrozen(ObjectNode body, String sb) {
		if (!vendor().equals(body.path("vendorId").asText()) || !body.path("requested").isBoolean()
			|| !body.path("requested").booleanValue() || !body.path("items").isArray() || body.path("items").size() != 1
			|| sb == null || !sb.equals(body.path("items").get(0).path("externalVendorSku").asText()))
			throw new IllegalStateException("쿠팡 검토 요청의 계정·SB·단일 옵션·심사 요청이 다릅니다.");
	}

	private boolean sameGallery(JsonNode expected, JsonNode actual) {
		if (!expected.isArray() || !actual.isArray() || expected.size() != actual.size())
			return false;
		for (int i = 0; i < expected.size(); i++)
			for (String key : List.of("imageOrder", "imageType", "vendorPath"))
				if (!sameValue(expected.get(i).path(key), actual.get(i).path(key)))
					return false;
		return true;
	}

	private boolean sameValue(JsonNode expected, JsonNode actual) {
		if (expected.isNumber()) {
			try {
				return !actual.isMissingNode() && !actual.isNull()
					&& expected.decimalValue().compareTo(new BigDecimal(actual.asText())) == 0;
			} catch (Exception e) {
				return false;
			}
		}
		return expected.equals(actual);
	}

	private VerifiedMarketPublication mismatch(Map<String, String> ids, String field) {
		return new VerifiedMarketPublication(false, ids, "쿠팡 등록 후 검토 값 불일치 또는 관측 불가: " + field, false);
	}

	private JsonNode get(String path) {
		return request("GET", path, null);
	}

	private JsonNode request(String method, String path, Object body) {
		try {
			return parse(rest.requestWithBody(method, path, body));
		} catch (RuntimeException e) {
			throw e instanceof MarketTransferFailure ? e : MarketApiEvidence.transferFailure(e);
		}
	}

	private JsonNode success(JsonNode root) {
		JsonNode envelope = root;
		if ("200".equals(root.path("code").asText()) && root.path("data").has("code"))
			envelope = root.path("data");
		if ("ERROR".equals(envelope.path("code").asText()))
			throw new MarketTransferFailure("HTTP_400",
				"쿠팡이 등록 요청을 명시 거절했습니다. " + com.sbshop.agent.core.application.product.ProductMarketSyncService
					.sanitizeMarketMessage(envelope.path("message").asText()),
				null, null);
		if (!"SUCCESS".equals(envelope.path("code").asText()))
			throw new IllegalStateException("쿠팡 성공 응답을 확인할 수 없습니다.");
		return envelope;
	}

	private ObjectNode parse(String payload) {
		try {
			JsonNode node = mapper.readTree(payload);
			if (node == null || !node.isObject())
				throw new IllegalStateException("쿠팡 응답 객체가 없습니다.");
			return (ObjectNode)node;
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("쿠팡 응답 해석 실패", e);
		}
	}

	private String vendor() {
		String vendor = rest.resolveVendorId();
		if (vendor == null || !vendor.matches("A[0-9]{8}"))
			throw new IllegalStateException("쿠팡 연동 판매자 코드를 확인하세요.");
		return vendor;
	}

	private String account() {
		return MarketApiEvidence.account("COUPANG", vendor());
	}

	private void sameAccount(String value) {
		if (!Objects.equals(value, account()))
			throw new UnsupportedOperationException("쿠팡 등록 연동 계정이 변경되었습니다.");
	}

	private static void id(String value) {
		if (value == null || !value.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("쿠팡 등록 상품·카테고리·옵션 코드를 확인하세요.");
	}

	private static String text(JsonNode value) {
		if (!value.isTextual() || value.textValue().isBlank())
			throw new IllegalStateException("쿠팡 필수 값이 없습니다.");
		return value.textValue();
	}

	private static void url(String text) {
		try {
			URI uri = URI.create(text);
			if (text.length() > 200 || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
				|| uri.getUserInfo() != null || uri.getFragment() != null
				|| !Set.of(-1, 80, 443).contains(uri.getPort()))
				throw new IllegalArgumentException();
		} catch (Exception e) {
			throw new IllegalArgumentException("쿠팡 대표·추가 이미지 URL을 확인하세요.");
		}
	}
}
