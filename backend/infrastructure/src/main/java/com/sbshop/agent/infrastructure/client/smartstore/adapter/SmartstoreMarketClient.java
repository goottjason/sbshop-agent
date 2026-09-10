package com.sbshop.agent.infrastructure.client.smartstore.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.config.MarketRegistrationDefaults;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreAddressBookResolver;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreCategoryResolver;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreProductPayloadBuilder;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreUnitCapacity;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartstoreMarketClient implements MarketClient {
	private SmartstoreReviewedFields reviewedFields() {
		return new SmartstoreReviewedFields(restClient, objectMapper, this::uploadReviewedImages);
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.PreparedMarketFields prepareProductFields(Product product,
		String listingId, String optionId, Set<String> fields) {
		try {
			return reviewedFields().prepare(product, listingId, optionId, fields);
		} catch (Exception e) {
			if (e instanceof UnsupportedOperationException unsupported)
				throw unsupported;
			if (e instanceof IllegalArgumentException invalid)
				throw invalid;
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.MarketFieldsRead readProductFields(String listingId,
		String optionId, String expectedSbCode, Set<String> fields) {
		try {
			return reviewedFields().read(listingId, optionId, expectedSbCode, fields);
		} catch (Exception e) {
			if (e instanceof UnsupportedOperationException unsupported)
				throw unsupported;
			if (e instanceof IllegalArgumentException invalid)
				throw invalid;
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public void writePreparedProductFields(String listingId, String optionId, String expectedSbCode,
		com.sbshop.agent.core.domain.market.client.dto.PreparedMarketFields prepared, Runnable beforeWrite) {
		var guardFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
		try {
			reviewedFields().write(listingId, optionId, expectedSbCode, prepared, () -> {
				try {
					beforeWrite.run();
				} catch (RuntimeException abort) {
					guardFailure.set(abort);
					throw abort;
				}
			});
		} catch (Exception e) {
			if (e == guardFailure.get())
				throw guardFailure.get();
			if (e instanceof UnsupportedOperationException unsupported)
				throw unsupported;
			if (e instanceof IllegalArgumentException invalid)
				throw invalid;
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	private List<String> uploadReviewedImages(List<String> images) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		for (String url : images) {
			byte[] bytes = downloadImage(url);
			if (bytes == null || bytes.length == 0)
				throw new IllegalStateException("검토 이미지 다운로드에 실패했습니다.");
			body.add("imageFiles", new ByteArrayResource(bytes) {
				@Override
				public String getFilename() {
					return "image.jpg";
				}
			});
		}
		JsonNode response = restClient.uploadImages(body);
		if (response == null || !response.path("images").isArray() || response.path("images").size() != images.size())
			throw new IllegalStateException("네이버 이미지 전체 업로드를 확인하지 못했습니다.");
		List<String> urls = new ArrayList<>();
		for (JsonNode item : response.path("images"))
			urls.add(item.path("url").asText());
		return urls;
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication preparePublication(Product product,
		java.math.BigDecimal price) {
		String account = inspectionAccountReference();
		try {
			var context = mergeWithAuto(product,
				new MarketPublishContext(null, null, price, List.of(), Map.of(), Map.of()));
			Map<String, Object> body = payloadBuilder.build(product, context);
			applyUploadedImages(body, product);
			var node = (com.fasterxml.jackson.databind.node.ObjectNode)objectMapper.valueToTree(body);
			var origin = (com.fasterxml.jackson.databind.node.ObjectNode)node.path("originProduct");
			origin.put("stockQuantity", product.getSalesQuantity());
			if (product.getSalesQuantity() == 0)
				origin.put("statusType", "OUTOFSTOCK");
			if (account == null || !account.equals(inspectionAccountReference()))
				throw new IllegalStateException("등록 준비 중 마켓 계정이 변경되었습니다.");
			return new com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication(node.toString(),
				account,
				origin.path("name").asText(), origin.path("leafCategoryId").asText(), context.categoryPath(), price,
				product.getSalesQuantity(),
				origin.path("images").path("representativeImage").path("url").asText());
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public Map<String, String> submitPreparedPublication(Product product, String operationId, String payload) {
		try {
			// Frozen request: no category lookup, image upload or payload reconstruction after approval.
			JsonNode root = objectMapper.readTree(restClient.post("/v2/products", objectMapper.readTree(payload)));
			String id = root.path("originProductNo").asText("");
			requirePriceId(id);
			if (root.has("code"))
				throw new IllegalStateException("등록 응답의 결과 코드를 확인할 수 없습니다.");
			Map<String, String> result = new HashMap<>();
			result.put("originProductNo", id);
			String channel = root.path("smartstoreChannelProductNo").asText("");
			if (channel.matches("[1-9][0-9]{0,17}"))
				result.put("channelProductNo", channel);
			return result;
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public boolean verifyPreparedPublication(String id, String sbCode, String payload) {
		requirePriceId(id);
		try {
			JsonNode response = objectMapper.readTree(restClient.get("/v2/products/origin-products/" + id));
			JsonNode actual = response.path("originProduct"),
				expected = objectMapper.readTree(payload).path("originProduct");
			if (response.has("code") || !actual.isObject()
				|| (actual.hasNonNull("id") && !id.equals(actual.path("id").asText())))
				return false;
			if (!sbCode
				.equals(actual.path("detailAttribute").path("sellerCodeInfo").path("sellerManagementCode").asText()))
				return false;
			if (!java.util.Set.of("SALE", "OUTOFSTOCK", "WAIT").contains(actual.path("statusType").asText()))
				return false;
			for (String field : List.of("name", "leafCategoryId", "salePrice", "stockQuantity", "detailContent",
				"images")) {
				if (!actual.hasNonNull(field) || !actual.path(field).equals(expected.path(field)))
					return false;
			}
			return true;
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead readSalePrice(String id, String optionId) {
		requirePriceId(id);
		String account = inspectionAccountReference();
		try {
			JsonNode root = objectMapper.readTree(restClient.get("/v2/products/origin-products/" + id));
			JsonNode p = root.path("originProduct");
			if (root.has("code") || !p.isObject() || (p.hasNonNull("id") && !id.equals(p.path("id").asText())))
				throw new IllegalStateException("원상품 응답의 식별자를 확인할 수 없습니다.");
			String status = p.path("statusType").asText();
			// 판매중지(SUSPENSION)는 판매금지와 다르다. 가격은 중지 중에도
			// 수정할 수 있으므로, 영구 제한 상태만 차단한다.
			boolean writable = java.util.Set.of("SALE", "OUTOFSTOCK", "SUSPENSION").contains(status);
			if (!p.path("salePrice").isIntegralNumber() || p.path("salePrice").decimalValue().signum() <= 0)
				throw new IllegalStateException("조회 응답에 유효한 판매가가 없습니다.");
			if (account == null || !account.equals(inspectionAccountReference()))
				throw new IllegalStateException("조회 계정이 변경되었습니다.");
			return new com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead(
				p.path("salePrice").decimalValue(), writable,
				writable ? "원상품 판매가 조회" : "상품 상태 " + status + ": 자동 쓰기를 중지합니다.", account);
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public void writeSalePrice(String id, String optionId, java.math.BigDecimal price) {
		try {
			requirePriceId(id);
			// Official v2.87 multi-update contract. No name, stock, status or discount is sent.
			restClient.patch("/v1/products/origin-products/multi-update",
				Map.of("multiProductUpdateRequestVos", List.of(
					Map.of("originProductNo", Long.parseLong(id), "multiUpdateTypes", List.of("SALE_PRICE"),
						"productSalePrice", Map.of("salePrice", price.intValueExact())))));
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.MarketStockRead readStockQuantity(String id,
		String optionId, String expectedSbCode) {
		return observeStock(id, optionId, expectedSbCode, null).read();
	}

	/** API 2.88: STOCK-only PATCH for origin inventory; exact single-option PUT preserves defaulted fields. */
	@Override
	public void writeStockQuantity(String id, String optionId, String expectedSbCode, int quantity,
		String expectedAccountReference, Runnable beforeWrite) {
		if (quantity < 0 || quantity > 99999999)
			throw new IllegalArgumentException("스마트스토어 재고수량은 0~99,999,999의 정수여야 합니다.");
		if (expectedAccountReference == null || !expectedAccountReference.equals(inspectionAccountReference()))
			throw new UnsupportedOperationException("스마트스토어 연동 계정이 변경되었습니다.");
		var current = observeStock(id, optionId, expectedSbCode, quantity);
		if (!current.read().writable() || !expectedAccountReference.equals(current.read().accountReference()))
			throw new UnsupportedOperationException(current.read().reason());
		// A failed/expired durable intent must escape unwrapped and prevent either outbound write.
		beforeWrite.run();
		try {
			if (!expectedAccountReference.equals(inspectionAccountReference()))
				throw new IllegalStateException("전송 직전에 스마트스토어 계정이 변경되었습니다.");
			// SUSPENSION은 재고 부족에 따른 품절이 아니라 판매중지 상태다.
			// 재고가 다시 생긴 경우에만 명시적으로 SALE로 전환한다.
			if (current.statusType().equals("SUSPENSION") && quantity > 0)
				changeStatusToSale(id);
			String response = current.optionRequest() == null
				? restClient.patch("/v1/products/origin-products/multi-update",
					Map.of("multiProductUpdateRequestVos", List.of(Map.of("originProductNo", Long.parseLong(id),
						"multiUpdateTypes", List.of("STOCK"), "stockQuantity", quantity))))
				: restClient.put("/v1/products/origin-products/" + id + "/option-stock",
					Map.of("optionInfo", current.optionRequest()));
			// CommonResponse.code has no documented success enum. A receipt never supplies quantity proof.
			JsonNode receipt = response == null ? null : objectMapper.readTree(response);
			if (receipt == null || !receipt.isObject())
				throw new IllegalStateException("스마트스토어 수량 전송 응답이 불명확합니다. 실제 수량을 다시 조회하세요.");
			String code = receipt.path("code").asText();
			Integer rejection = Map.of("BAD_REQUEST", 400, "UNAUTHORIZED", 401, "FORBIDDEN", 403,
				"NOT_FOUND", 404, "INTERNAL_SERVER_ERROR", 500).get(code);
			if (rejection != null) {
				String detail = com.sbshop.agent.core.application.product.ProductMarketSyncService
					.sanitizeMarketMessage(
						code + ": " + receipt.path("message").asText("마켓이 수량 요청을 거절했습니다."));
				throw new com.sbshop.agent.core.domain.market.sync.MarketTransferFailure("HTTP_" + rejection,
					detail, null, null);
			}
			if (!expectedAccountReference.equals(inspectionAccountReference()))
				throw new IllegalStateException("수량 전송 중 스마트스토어 계정이 변경되었습니다. 재확인이 필요합니다.");
		} catch (com.sbshop.agent.core.domain.market.sync.MarketTransferFailure e) {
			throw e;
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	private record StockObservation(com.sbshop.agent.core.domain.market.client.dto.MarketStockRead read,
		Map<String, Object> optionRequest, String statusType) {
	}

	private StockObservation observeStock(String id, String expectedOption, String sbCode, Integer desiredQuantity) {
		requirePriceId(id);
		String account = inspectionAccountReference();
		try {
			JsonNode root = objectMapper.readTree(restClient.get("/v2/products/origin-products/" + id));
			JsonNode product = root.path("originProduct");
			JsonNode sellerCode = product.path("detailAttribute").path("sellerCodeInfo").path("sellerManagementCode");
			// The official GET response omits origin id; exact GET path + account + SB identify its origin.
			if (root.has("code") || !product.isObject()
				|| product.has("id") && !id.equals(product.path("id").asText())
				|| sbCode == null || sbCode.isBlank() || !sellerCode.isTextual()
				|| !sbCode.equals(sellerCode.textValue()))
				throw new IllegalStateException("스마트스토어 계정·원상품·SB코드 연결을 확인할 수 없습니다.");
			JsonNode options = product.path("detailAttribute").path("optionInfo");
			if (!options.isMissingNode() && !options.isNull() && !options.isObject())
				throw new IllegalStateException("스마트스토어 옵션 구조를 확인할 수 없습니다.");
			JsonNode combinations = options.path("optionCombinations"), standards = options.path("optionStandards");
			for (JsonNode list : List.of(combinations, standards)) {
				if (!list.isMissingNode() && !list.isNull() && !list.isArray())
					throw new IllegalStateException("스마트스토어 옵션 목록 형식이 올바르지 않습니다.");
			}
			if (combinations.size() + standards.size() > 1)
				throw new UnsupportedOperationException("여러 스마트스토어 옵션의 SB별 대응 확인이 필요합니다. 첫 옵션이나 총수량을 임의로 전송하지 않습니다.");
			String optionId = "ORIGIN:" + id;
			int quantity;
			boolean usable = true;
			Map<String, Object> request = null;
			if (combinations.isEmpty() && standards.isEmpty()) {
				quantity = stockInteger(product.path("stockQuantity"));
			} else {
				boolean standard = !standards.isEmpty();
				JsonNode option = (standard ? standards : combinations).get(0);
				String rawId = option.path("id").asText();
				requirePriceId(rawId);
				if (!option.path("usable").isBoolean())
					throw new IllegalStateException("스마트스토어 옵션 사용 여부가 확인되지 않았습니다.");
				if (option.hasNonNull("skuYn") && !option.path("skuYn").isBoolean())
					throw new IllegalStateException("옵션 SKU 재고 관리 여부를 확인할 수 없습니다.");
				if (option.path("skuYn").asBoolean(false))
					throw new UnsupportedOperationException("풀필먼트 SKU와 연결된 옵션은 수량 관리 주체 확인이 필요합니다.");
				if (standard && (!options.path("useStockManagement").isBoolean()
					|| !options.path("useStockManagement").booleanValue()))
					throw new UnsupportedOperationException("표준형 옵션 재고 관리가 활성화되지 않았습니다. 설정을 자동 변경하지 않습니다.");
				quantity = stockInteger(option.path("stockQuantity"));
				usable = option.path("usable").booleanValue();
				optionId = (standard ? "STANDARD:" : "COMBINATION:") + rawId;
				Map<String, Object> item = new java.util.LinkedHashMap<>();
				item.put("id", Long.parseLong(rawId));
				item.put("stockQuantity", desiredQuantity == null ? quantity : desiredQuantity);
				item.put("usable", usable);
				if (!standard) {
					JsonNode price = option.path("price");
					if (!price.isIntegralNumber() || !price.canConvertToInt())
						throw new IllegalStateException("조합형 옵션가를 정확히 보존할 수 없어 수량 전송을 보류합니다.");
					item.put("price", price.intValue());
				}
				request = new java.util.LinkedHashMap<>();
				request.put(standard ? "optionStandards" : "optionCombinations", List.of(item));
				if (options.hasNonNull("useStockManagement")) {
					if (!options.path("useStockManagement").isBoolean())
						throw new IllegalStateException("옵션 재고 관리 설정을 확인할 수 없습니다.");
					request.put("useStockManagement", options.path("useStockManagement").booleanValue());
				}
			}
			if (expectedOption != null && !expectedOption.equals(optionId))
				throw new IllegalStateException("스마트스토어 옵션 종류·번호가 변경되었습니다. 다시 검토하세요.");
			if (account == null || !account.equals(inspectionAccountReference()))
				throw new IllegalStateException("조회 중 스마트스토어 계정이 변경되었습니다.");
			String status = product.path("statusType").asText();
			boolean writable = usable && Set.of("SALE", "OUTOFSTOCK", "SUSPENSION").contains(status);
			var proof = new com.sbshop.agent.core.domain.market.client.dto.MarketStockRead(quantity, writable,
				writable ? ("SUSPENSION".equals(status)
					? "스마트스토어 판매중지 상품·단일 옵션 수량 확인"
					: "스마트스토어 원상품·단일 옵션 수량 확인")
					: "상품 상태 " + status + " 또는 옵션 사용 중지: 판매 재개·사용 설정 변경 없이 보류합니다.",
				account, optionId);
			return new StockObservation(proof, request, status);
		} catch (UnsupportedOperationException blocked) {
			throw blocked;
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	private void changeStatusToSale(String id) {
		try {
			String response = restClient.put("/v1/products/origin-products/" + id + "/change-status",
				Map.of("statusType", "SALE"));
			if (response != null && !response.isBlank()) {
				JsonNode receipt = objectMapper.readTree(response);
				if (receipt.has("code"))
					throw new IllegalStateException("스마트스토어 판매재개 거절: " + receipt.path("message").asText("응답 코드 확인 필요"));
			}
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	private int stockInteger(JsonNode quantity) {
		if (!quantity.isIntegralNumber() || !quantity.canConvertToInt() || quantity.intValue() < 0
			|| quantity.intValue() > 99999999)
			throw new IllegalStateException("스마트스토어 재고수량이 유효한 0~99,999,999 정수가 아닙니다.");
		return quantity.intValue();
	}

	private static void requirePriceId(String id) {
		if (id == null || !id.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("원상품 번호가 올바르지 않습니다.");
	}

	private final SmartstoreProductPayloadBuilder payloadBuilder;
	private final SmartstoreCategoryResolver categoryResolver;
	private final SmartstoreAddressBookResolver addressBookResolver;
	private final MarketRegistrationDefaults defaults;
	private final SmartstoreRestClient restClient;
	private final ObjectMapper objectMapper;

	private static final int MAX_PUBLISH_IMAGES = 10;
	private static final int CATALOG_PAGE_SIZE = 500;
	private static final int CATALOG_MAX_PAGES = 200;

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.SMART_STORE;
	}

	@Override
	public Map<String, String> publish(Product product) {
		return publish(product, autoContext(product));
	}

	@Override
	public Map<String, String> publish(Product product, MarketPublishContext context) {
		context = mergeWithAuto(product, context);
		log.info("[Smartstore] 상품 등록 시작: {}", product.getSbCode());
		try {
			Map<String, Object> requestBody = payloadBuilder.build(product, context);
			applyUploadedImages(requestBody, product);
			String response = restClient.post("/v2/products", requestBody);
			JsonNode node = objectMapper.readTree(response);

			String originProductNo = node.path("originProductNo").asText("");
			if (originProductNo.isEmpty()) {
				originProductNo = node.path("originProduct").path("originProductNo").asText("");
			}
			if (originProductNo.isEmpty()) {
				throw new RuntimeException("스마트스토어 등록 응답에 originProductNo가 없습니다: "
					+ response.substring(0, Math.min(response.length(), 300)));
			}
			String channelProductNo = node.path("smartstoreChannelProductNo").asText("");

			log.info("[Smartstore] 상품 등록 성공: originProductNo={}, channelProductNo={}",
				originProductNo, channelProductNo);
			Map<String, String> identifiers = new HashMap<>();
			identifiers.put("originProductNo", originProductNo);
			if (!channelProductNo.isEmpty()) {
				identifiers.put("channelProductNo", channelProductNo);
			}
			return identifiers;
		} catch (RuntimeException e) {
			log.error("[Smartstore] 상품 등록 실패: {}", e.getMessage());
			throw e;
		} catch (Exception e) {
			log.error("[Smartstore] 상품 등록 실패: {}", e.getMessage());
			throw new RuntimeException("Smartstore 상품 등록 오류", e);
		}
	}

	@Override
	public String inspectionAccountReference() {
		return restClient.accountReference();
	}

	@Override
	public com.sbshop.agent.core.domain.market.MarketPresence checkPresence(String marketItemId) {
		return switch (inspectListing(marketItemId).state()) {
			case DELETED -> com.sbshop.agent.core.domain.market.MarketPresence.ABSENT;
			case UNKNOWN -> com.sbshop.agent.core.domain.market.MarketPresence.UNKNOWN;
			default -> com.sbshop.agent.core.domain.market.MarketPresence.PRESENT;
		};
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation inspectListing(String marketItemId) {
		var unknown = com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation
			.unknown("응답 또는 조회 계정을 확인할 수 없습니다. 연결을 유지합니다.");
		if (marketItemId == null || !marketItemId.matches("[0-9]+"))
			return unknown;
		String account = restClient.accountReference();
		if (account == null)
			return unknown;
		String path = "/v2/products/origin-products/" + marketItemId;
		var result = inspectListingResponse(path, marketItemId, account);
		return account.equals(restClient.accountReference()) ? result : unknown;
	}

	private com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation inspectListingResponse(String path,
		String id, String account) {
		var state = com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.UNKNOWN;
		String code = "UNVERIFIED";
		java.time.Instant retryAfter = null;
		String detail = "응답 형식을 확인하지 못했습니다. 연결을 유지합니다.";
		try {
			JsonNode response = objectMapper.readTree(restClient.get(path));
			JsonNode origin = response == null ? null : response.path("originProduct");
			if (response != null && !response.has("code") && origin.isObject()
				&& !origin.path("name").asText("").isBlank()
				&& (!origin.hasNonNull("id") || id.equals(origin.path("id").asText()))) {
				code = origin.path("statusType").asText("");
				state = switch (code) {
					case "DELETE" ->
						com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.DELETED;
					case "PROHIBITION" ->
						com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.PROHIBITED;
					case "OUTOFSTOCK" ->
						com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.OUT_OF_STOCK;
					case "SUSPENSION", "CLOSE" ->
						com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.STOPPED;
					case "WAIT", "SALE", "UNADMISSION", "REJECTION" ->
						com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.PRESENT;
					default -> com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.UNKNOWN;
				};
				detail = "마켓 상품 상태: " + code + " · 세부 사유 미제공";
			}
		} catch (Exception error) {
			detail = "조회 오류로 확인하지 못했습니다. 연결을 유지하고 다시 조회하세요.";
			for (Throwable cause = error; cause != null; cause = cause.getCause()) {
				if (cause instanceof org.springframework.web.client.ResourceAccessException)
					code = "TRANSPORT_ERROR";
				if (cause instanceof org.springframework.web.client.RestClientResponseException http) {
					code = "HTTP_" + http.getStatusCode().value();
					retryAfter = com.sbshop.agent.infrastructure.client.smartstore.client.InspectionRetryAfter.parse(
						http.getResponseHeaders() == null ? null : http.getResponseHeaders().getFirst("Retry-After"),
						java.time.Instant.now());
					try {
						String apiCode = objectMapper.readTree(http.getResponseBodyAsString()).path("code").asText("");
						code += "/" + apiCode;
						if (http.getStatusCode().value() == 404 && "NOT_FOUND".equals(apiCode)) {
							state = com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.DELETED;
							detail = "사유 미제공 · 마켓 상품번호 조회로 부재 확인";
						}
					} catch (Exception ignored) {}
					break;
				}
				if (cause.getCause() == cause)
					break;
			}
		}
		return new com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation(state, code, detail,
			account, "GET " + path, java.time.Instant.now(), retryAfter);
	}

	@Override
	public MarketItemInfo extractMarketItem(String marketItemId) {
		String response = restClient.get("/v2/products/origin-products/" + marketItemId);
		try {
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			return MarketItemInfo.builder()
				.isMasterData(true)
				.name(originNode.path("productName").asText(null))
				.mappingKey(originNode.path("productCode").asText(""))
				.salePrice(BigDecimal.valueOf(originNode.path("salePrice").asDouble(0)))
				.stock(originNode.path("stockQuantity").asInt(0))
				.rawData(objectMapper.convertValue(originNode, Map.class))
				.build();
		} catch (Exception e) {
			log.error("[Smartstore] 상품 정보 추출 실패 (ID: {}): {}", marketItemId, e.getMessage());
			throw new RuntimeException("Smartstore 데이터 추출 오류", e);
		}
	}

	@Override
	public MarketItemInfo parseLocalData(Map<String, Object> rawData) {
		if (rawData == null || rawData.isEmpty()) {
			return MarketItemInfo.builder().build();
		}
		return MarketItemInfo.builder()
			.isMasterData(true)
			.name(rawData.get("productName") != null ? String.valueOf(rawData.get("productName")) : null)
			.salePrice(
				rawData.get("salePrice") != null ? new BigDecimal(String.valueOf(rawData.get("salePrice"))) : null)
			.stock(rawData.get("stockQuantity") != null ? Integer.parseInt(String.valueOf(rawData.get("stockQuantity")))
				: null)
			.rawData(rawData)
			.build();
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut) {
		return syncPriceAndStock(marketItemId, currentRawData, price, quantity, soldOut, null);
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut, Product product) {
		try {
			String response = restClient.get("/v2/products/origin-products/" + marketItemId);
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			Map<String, Object> originProduct = objectMapper.convertValue(originNode, Map.class);

			applyUnitPrice(originProduct, product);

			if (price != null)
				originProduct.put("salePrice", price);
			if (soldOut) {
				originProduct.put("stockQuantity", 0);
			} else {
				originProduct.put("stockQuantity", quantity);
			}
			normalizeReadOnlyStatusType(originProduct);

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);
			restClient.put("/v2/products/origin-products/" + marketItemId, requestBody);

			log.info("[Smartstore] 가격/재고/판매상태 업데이트 완료: {}", marketItemId);
			if (currentRawData != null) {
				if (price != null)
					currentRawData.put("salePrice", price);
				currentRawData.put("stockQuantity", soldOut ? 0 : quantity);
			}
		} catch (RuntimeException e) {
			log.error("[Smartstore] 가격/재고 업데이트 실패: {}", e.getMessage());
			throw e;
		} catch (Exception e) {
			log.error("[Smartstore] 가격/재고 업데이트 실패: {}", e.getMessage());
			throw new RuntimeException("[Smartstore] 가격/재고 업데이트 실패", e);
		}
		return currentRawData;
	}

	@Override
	public Optional<String> removeSellerImmediateDiscount(String marketItemId, boolean dryRun) {
		try {
			String response = restClient.get("/v2/products/origin-products/" + marketItemId);
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			Map<String, Object> originProduct = objectMapper.convertValue(originNode, Map.class);

			Object benefitObj = originProduct.get("customerBenefit");
			if (!(benefitObj instanceof Map)) {
				return Optional.empty();
			}
			@SuppressWarnings("unchecked") Map<String, Object> customerBenefit = (Map<String, Object>)benefitObj;
			Object immediate = customerBenefit.get("immediateDiscountPolicy");
			if (immediate == null) {
				return Optional.empty();
			}
			String description = String.valueOf(immediate);

			if (dryRun) {
				log.info("[Smartstore] 즉시할인 확인(dryRun): {} — {}", marketItemId, description);
				return Optional.of(description);
			}

			customerBenefit.remove("immediateDiscountPolicy");
			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);
			restClient.put("/v2/products/origin-products/" + marketItemId, requestBody);
			log.info("[Smartstore] 즉시할인 제거 완료: {} — 제거값 {}", marketItemId, description);
			return Optional.of(description);
		} catch (RuntimeException e) {
			log.error("[Smartstore] 즉시할인 제거 실패: {} — {}", marketItemId, e.getMessage());
			throw e;
		} catch (Exception e) {
			log.error("[Smartstore] 즉시할인 제거 실패: {} — {}", marketItemId, e.getMessage());
			throw new RuntimeException("[Smartstore] 즉시할인 제거 실패", e);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public boolean syncBarcode(Product product, String marketItemId, Map<String, Object> currentRawData) {
		String barcode = (product.getProductSpec() == null) ? null : product.getProductSpec().getBarcode();
		if (barcode == null || barcode.isBlank()) {
			log.info("[스토어] 바코드 없음 — 전송 생략: {}", marketItemId);
			return false;
		}
		String path = "/v2/products/origin-products/" + marketItemId;
		Map<String, Object> originProduct;
		try {
			JsonNode originNode = objectMapper.readTree(restClient.get(path)).path("originProduct");
			originProduct = objectMapper.convertValue(originNode, Map.class);
		} catch (Exception e) {
			throw new IllegalStateException("스토어 상품 조회 실패 — 바코드 전송 중단: " + marketItemId, e);
		}
		if (originProduct == null || originProduct.isEmpty()) {
			throw new IllegalStateException("스토어 상품 조회 응답에 originProduct 없음: " + marketItemId);
		}
		Map<String, Object> attr = (Map<String, Object>)originProduct
			.computeIfAbsent("detailAttribute", k -> new HashMap<String, Object>());
		Map<String, Object> sellerCodeInfo = (Map<String, Object>)attr
			.computeIfAbsent("sellerCodeInfo", k -> new HashMap<String, Object>());
		Object currentBarcode = sellerCodeInfo.get("sellerBarcode");
		if (currentBarcode != null && barcode.equals(String.valueOf(currentBarcode).trim())) {
			log.info("[스토어] 바코드가 이미 마켓과 같다 — PUT 생략: {} barcode={}", marketItemId, barcode);
			return false;
		}
		sellerCodeInfo.put("sellerBarcode", barcode);
		backfillConsumptionDate(attr);
		backfillUnitPriceYn(attr, product);

		normalizeReadOnlyStatusType(originProduct);
		Map<String, Object> requestBody = new HashMap<>();
		requestBody.put("originProduct", originProduct);
		restClient.put(path, requestBody);
		log.info("[스토어] 바코드 전송 완료: {} barcode={}", marketItemId, barcode);
		return true;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, Object> syncProductFields(Product product, String marketItemId,
		Map<String, Object> currentRawData, Set<MarketEditField> fields) {
		String path = "/v2/products/origin-products/" + marketItemId;
		Map<String, Object> originProduct;
		try {
			JsonNode originNode = objectMapper.readTree(restClient.get(path)).path("originProduct");
			originProduct = objectMapper.convertValue(originNode, Map.class);
		} catch (Exception e) {
			throw new IllegalStateException("스토어 상품 조회 실패 — 필드 수정 중단: " + marketItemId, e);
		}
		if (originProduct == null || originProduct.isEmpty()) {
			throw new IllegalStateException("스토어 상품 조회 응답에 originProduct 없음: " + marketItemId);
		}

		if (fields.contains(MarketEditField.PRODUCT_NAME)) {
			String name = product.getProductName();
			if (name != null && !name.isBlank()) {
				originProduct.put("name", name);
			}
		}

		Map<String, Object> attr = (Map<String, Object>)originProduct
			.computeIfAbsent("detailAttribute", k -> new HashMap<String, Object>());

		if (fields.contains(MarketEditField.BRAND) || fields.contains(MarketEditField.MANUFACTURER)) {
			Map<String, Object> searchInfo = (Map<String, Object>)attr
				.computeIfAbsent("naverShoppingSearchInfo", k -> new HashMap<String, Object>());
			if (fields.contains(MarketEditField.BRAND)) {
				String brand = product.getBrand();
				if (brand != null && !brand.isBlank()) {
					searchInfo.put("brandName", brand);
				}
			}
			if (fields.contains(MarketEditField.MANUFACTURER)) {
				String manufacturer = manufacturerValue(product);
				if (manufacturer != null && !manufacturer.isBlank()) {
					searchInfo.put("manufacturerName", manufacturer);
				}
			}
		}

		backfillConsumptionDate(attr);
		backfillUnitPriceYn(attr, product);
		normalizeReadOnlyStatusType(originProduct);

		Map<String, Object> requestBody = new HashMap<>();
		requestBody.put("originProduct", originProduct);
		restClient.put(path, requestBody);
		log.info("[스토어] 필드 수정 완료: {} fields={}", marketItemId, fields);

		if (currentRawData != null) {
			currentRawData.putAll(originProduct);
			return currentRawData;
		}
		return originProduct;
	}

	private static String manufacturerValue(Product product) {
		if (product.getSourcingInfo() != null) {
			String value = product.getSourcingInfo().getManufacturer();
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return product.getBrand();
	}

	@Override
	@SuppressWarnings("unchecked")
	public boolean repairProductNotice(Product product, String marketItemId) {
		String path = "/v2/products/origin-products/" + marketItemId;
		Map<String, Object> originProduct;
		try {
			JsonNode originNode = objectMapper.readTree(restClient.get(path)).path("originProduct");
			originProduct = objectMapper.convertValue(originNode, Map.class);
		} catch (Exception e) {
			throw new IllegalStateException("스토어 상품 조회 실패 — 고시정보 보정 중단: " + marketItemId, e);
		}
		if (originProduct == null || originProduct.isEmpty()) {
			throw new IllegalStateException("스토어 상품 조회 응답에 originProduct 없음: " + marketItemId);
		}
		Object rawAttr = originProduct.get("detailAttribute");
		if (!(rawAttr instanceof Map)) {
			return false;
		}
		Map<String, Object> attr = (Map<String, Object>)rawAttr;
		if (!backfillConsumptionDate(attr)) {
			return false;
		}
		backfillUnitPriceYn(attr, product);
		normalizeReadOnlyStatusType(originProduct);
		Map<String, Object> requestBody = new HashMap<>();
		requestBody.put("originProduct", originProduct);
		restClient.put(path, requestBody);
		log.info("[스토어] 고시정보 보정 완료: {}", marketItemId);
		return true;
	}

	private static void normalizeReadOnlyStatusType(Map<String, Object> originProduct) {
		Object current = originProduct.get("statusType");
		if ("OUTOFSTOCK".equals(current)) {
			originProduct.put("statusType", "SALE");
			log.info("[스토어] statusType=OUTOFSTOCK → SALE 치환 — 조회 전용 파생값이라 "
				+ "그대로도 빈 값으로도 PUT 이 거부된다. 재고 0 이면 네이버가 다시 품절로 표시한다");
			return;
		}
		if (current == null || String.valueOf(current).isBlank()) {
			originProduct.put("statusType", "SALE");
			log.info("[스토어] statusType 이 비어 SALE 로 채운다 — 옛 상품은 이 값이 없어 "
				+ "NotValidEnum 으로 모든 수정이 거부된다");
		}
	}

	@SuppressWarnings("unchecked")
	private static boolean backfillUnitPriceYn(Map<String, Object> detailAttribute, Product product) {
		Object raw = detailAttribute.get("unitCapacity");
		Map<String, Object> unitCapacity = (raw instanceof Map)
			? (Map<String, Object>)raw : new HashMap<>();
		if (unitCapacity.get("unitPriceYn") != null) {
			return false;
		}
		unitCapacity.putAll(SmartstoreUnitCapacity.of(product));
		detailAttribute.put("unitCapacity", unitCapacity);
		log.info("[스토어] 단위가격 사용여부 미선택 보정: unitPriceYn={}", unitCapacity.get("unitPriceYn"));
		return true;
	}

	@SuppressWarnings("unchecked")
	private static boolean backfillConsumptionDate(Map<String, Object> detailAttribute) {
		Object rawNotice = detailAttribute.get("productInfoProvidedNotice");
		if (!(rawNotice instanceof Map)) {
			return false;
		}
		boolean changed = false;
		Map<String, Object> notice = (Map<String, Object>)rawNotice;
		for (Map.Entry<String, Object> entry : notice.entrySet()) {
			if (!(entry.getValue() instanceof Map)) {
				continue;
			}
			Map<String, Object> block = (Map<String, Object>)entry.getValue();
			Object current = block.get("consumptionDateText");
			if (current != null && !String.valueOf(current).isBlank()) {
				continue;
			}
			Object expiration = block.get("expirationDateText");
			String filled = (expiration != null && !String.valueOf(expiration).isBlank())
				? String.valueOf(expiration) : "상세설명 참조";
			block.put("consumptionDateText", filled);
			changed = true;
			log.info("[스토어] 소비기한 미입력 고시정보 보정: block={} value={}", entry.getKey(), filled);
		}
		return changed;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(Product product,
		String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		try {
			String response = restClient.get("/v2/products/origin-products/" + marketItemId);
			log.info("[D092][스토어] origin-products GET (len={}): {}", response == null ? -1 : response.length(),
				response == null ? "null" : response.substring(0, Math.min(response.length(), 3000)));
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			Map<String, Object> originProduct = objectMapper.convertValue(originNode, Map.class);

			if (!hostedImages.isEmpty()) {
				List<String> targetImages = uploadImagesToNaver(hostedImages).stream()
					.map(this::ensureImageExtension).toList();
				applyImages(originProduct, targetImages);
			}
			applyCustomsTaxType(originProduct);
			applyUnitPrice(originProduct, product);
			if (newDetailHtml != null) {
				originProduct.put("detailContent", newDetailHtml);
			}

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);
			try {
				log.info("[D092][스토어] PUT images={}, detailContent(len={})",
					objectMapper.writeValueAsString(originProduct.get("images")),
					newDetailHtml == null ? -1 : newDetailHtml.length());
			} catch (Exception ignore) {}
			String putResp = restClient.put("/v2/products/origin-products/" + marketItemId, requestBody);
			log.info("[D092][스토어] PUT resp: {}", putResp);

			log.info("[Smartstore] 이미지/HTML 동기화 완료: {}", marketItemId);
			if (currentRawData != null) {
				if (!hostedImages.isEmpty())
					currentRawData.put("representativeImage", Map.of("url", hostedImages.get(0)));
				if (newDetailHtml != null)
					currentRawData.put("detailContent", newDetailHtml);
			}
		} catch (RuntimeException e) {
			log.error("[Smartstore] 이미지/HTML 동기화 실패: {}", e.getMessage());
			throw e;
		} catch (Exception e) {
			log.error("[Smartstore] 이미지/HTML 동기화 실패: {}", e.getMessage());
			throw new RuntimeException("[Smartstore] 이미지/HTML 동기화 실패: " + e.getMessage(), e);
		}
		return currentRawData;
	}

	@Override
	public Map<String, String> fetchAllLinkIdentifiers(long throttleMs) {
		return fetchAllChannelProductNos(throttleMs);
	}

	@Override
	public List<MarketCatalogEntry> fetchCatalog(long throttleMs) {
		List<MarketCatalogEntry> entries = new ArrayList<>();
		int page = 1;
		boolean reachedLastPage = false;
		while (page <= CATALOG_MAX_PAGES) {
			String response;
			try {
				Map<String, Object> body = new HashMap<>();
				body.put("page", page);
				body.put("size", CATALOG_PAGE_SIZE);
				response = restClient.post("/v1/products/search", body);
			} catch (Exception e) {
				throw new RuntimeException(
					"[Smartstore] 전체 상품 조회 실패 page=" + page + " (누적 " + entries.size() + "건): " + e.getMessage(), e);
			}
			JsonNode root;
			try {
				root = objectMapper.readTree(response);
			} catch (Exception e) {
				throw new RuntimeException(
					"[Smartstore] 전체 상품 조회 실패 — 응답 파싱 불가 page=" + page + ": " + e.getMessage(), e);
			}
			JsonNode contents = root.path("contents");
			for (JsonNode content : contents) {
				MarketCatalogEntry entry = toCatalogEntry(content);
				if (entry != null) {
					entries.add(entry);
				}
			}
			boolean last = root.path("last").asBoolean(true) || !contents.isArray() || contents.isEmpty();
			log.info("[Smartstore] 카탈로그 스캔 page={} 누적 {}건 (last={})", page, entries.size(), last);
			if (last) {
				reachedLastPage = true;
				break;
			}
			page++;
			sleepQuietly(throttleMs);
		}
		if (!reachedLastPage) {
			throw new RuntimeException("[Smartstore] 전체 상품 조회 실패 — 페이지 상한("
				+ CATALOG_MAX_PAGES + ")을 소진했는데 마지막 페이지에 닿지 못했다 (누적 " + entries.size()
				+ "건). 잘린 카탈로그는 대조에서 '마켓에 없는 상품'으로 오독되므로 반환하지 않는다.");
		}
		return entries;
	}

	private MarketCatalogEntry toCatalogEntry(JsonNode content) {
		String originNo = text(content, "originProductNo");
		if (originNo.isEmpty()) {
			return null;
		}
		Map<String, String> identifiers = new HashMap<>();
		identifiers.put("originProductNo", originNo);
		JsonNode channel = pickChannelProduct(content.path("channelProducts"));
		String sellerCode = null;
		String status = null;
		if (channel != null) {
			String ch = text(channel, "channelProductNo");
			if (!ch.isEmpty()) {
				identifiers.put("channelProductNo", ch);
			}
			sellerCode = blankToNull(text(channel, "sellerManagementCode"));
			status = blankToNull(text(channel, "statusType"));
		}
		return new MarketCatalogEntry(sellerCode, identifiers, status);
	}

	private static String text(JsonNode node, String field) {
		String value = node.path(field).asText("");
		return value == null ? "" : value.trim();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private void sleepQuietly(long throttleMs) {
		if (throttleMs <= 0) {
			return;
		}
		try {
			Thread.sleep(throttleMs);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("[Smartstore] 전체 상품 조회 실패 — 중단됨", ie);
		}
	}

	public Map<String, String> fetchAllChannelProductNos(long throttleMs) {
		Map<String, String> all = new HashMap<>();
		int page = 1;
		int maxPages = 200;
		while (page <= maxPages) {
			try {
				Map<String, Object> body = new HashMap<>();
				body.put("page", page);
				body.put("size", 500);
				String response = restClient.post("/v1/products/search", body);
				JsonNode root = objectMapper.readTree(response);
				JsonNode contents = root.path("contents");
				for (JsonNode content : contents) {
					String originNo = content.path("originProductNo").asText("");
					if (originNo.isBlank()) {
						continue;
					}
					String ch = pickChannelProductNo(content.path("channelProducts"));
					if (ch != null) {
						all.put(originNo, ch);
					}
				}
				boolean last = root.path("last").asBoolean(true) || !contents.isArray() || contents.isEmpty();
				log.info("[Smartstore] 전체 채널상품 스캔 page={} 누적 {}건 (last={})", page, all.size(), last);
				if (last) {
					break;
				}
				page++;
				if (throttleMs > 0) {
					Thread.sleep(throttleMs);
				}
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.warn("[Smartstore] 전체 채널상품 스캔 실패 page={}: {}", page, e.getMessage());
				break;
			}
		}
		return all;
	}

	public Optional<String> fetchChannelProductNo(String originProductNo) {
		if (originProductNo == null || originProductNo.isBlank()) {
			return Optional.empty();
		}
		try {
			long originNo = Long.parseLong(originProductNo.trim());
			Map<String, Object> body = new HashMap<>();
			body.put("originProductNos", List.of(originNo));
			body.put("page", 1);
			body.put("size", 50);

			String response = restClient.post("/v1/products/search", body);
			JsonNode root = objectMapper.readTree(response);

			for (JsonNode content : root.path("contents")) {
				if (!originProductNo.trim().equals(content.path("originProductNo").asText())) {
					continue;
				}
				String ch = pickChannelProductNo(content.path("channelProducts"));
				if (ch != null) {
					return Optional.of(ch);
				}
			}
			return Optional.empty();
		} catch (Exception e) {
			log.warn("[Smartstore] channelProductNo 조회 실패: {}", e.getMessage());
			return Optional.empty();
		}
	}

	@Override
	public void deleteFromMarket(String marketItemId) {
		log.info("[Smartstore] 상품 삭제 시작: originProductNo={}", marketItemId);
		try {
			restClient.delete("/v2/products/origin-products/" + marketItemId);
			log.info("[Smartstore] 상품 삭제 성공: originProductNo={}", marketItemId);
		} catch (RuntimeException e) {
			log.error("[Smartstore] 상품 삭제 실패 (originProductNo: {}): {}", marketItemId, e.getMessage());
			throw e;
		}
	}

	private MarketPublishContext autoContext(Product product) {
		var category = categoryResolver.resolve(null, product.getProductName(), product.getBrand());
		Map<String, Object> extra = new HashMap<>(addressBookResolver.resolve());
		extra.put("afterServiceTelephoneNumber", defaults.getSmartstoreAfterServiceTelephone());
		extra.put("afterServiceGuideContent", defaults.getSmartstoreAfterServiceGuide());
		extra.put("returnDeliveryFee", defaults.getSmartstoreReturnDeliveryFee());
		extra.put("exchangeDeliveryFee", defaults.getSmartstoreExchangeDeliveryFee());
		extra.put("originAreaCode", defaults.getSmartstoreOriginAreaCode());
		return new MarketPublishContext(
			category.categoryId(), category.categoryPath(), product.getSalePrice(),
			List.of(), Map.of(), extra);
	}

	private MarketPublishContext mergeWithAuto(Product product, MarketPublishContext context) {
		if (context.hasCategory() && !context.extraFields().isEmpty()) {
			return context;
		}
		MarketPublishContext auto = autoContext(product);
		Map<String, Object> extra = new HashMap<>(auto.extraFields());
		extra.putAll(context.extraFields());
		return new MarketPublishContext(
			context.hasCategory() ? context.categoryId() : auto.categoryId(),
			context.categoryPath() != null ? context.categoryPath() : auto.categoryPath(),
			context.salePrice() != null ? context.salePrice() : auto.salePrice(),
			context.keywords().isEmpty() ? auto.keywords() : context.keywords(),
			context.noticeFields().isEmpty() ? auto.noticeFields() : context.noticeFields(),
			extra);
	}

	@SuppressWarnings("unchecked")
	private void applyCustomsTaxType(Map<String, Object> originProduct) {
		Object da = originProduct.get("detailAttribute");
		Map<String, Object> detailAttribute = (da instanceof Map)
			? (Map<String, Object>)da : new HashMap<>();
		detailAttribute.put("customsTaxType", "INCLUDED");
		originProduct.put("detailAttribute", detailAttribute);
	}

	@SuppressWarnings("unchecked")
	private void applyUnitPrice(Map<String, Object> originProduct, Product product) {
		Object da = originProduct.get("detailAttribute");
		Map<String, Object> detailAttribute = (da instanceof Map)
			? (Map<String, Object>)da : new HashMap<>();
		detailAttribute.put("unitCapacity", SmartstoreUnitCapacity.of(product));
		originProduct.put("detailAttribute", detailAttribute);
	}

	private List<String> uploadImagesToNaver(List<String> hostedImages) {
		try {
			MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
			for (String imageUrl : hostedImages) {
				byte[] bytes = downloadImage(imageUrl);
				if (bytes != null) {
					ByteArrayResource res = new ByteArrayResource(
						bytes) {
						@Override
						public String getFilename() {
							return "image.jpg";
						}
					};
					body.add("imageFiles", res);
				}
			}
			if (!body.isEmpty()) {
				JsonNode resp = restClient.uploadImages(body);
				if (resp != null && resp.has("images")) {
					List<String> naverUrls = new ArrayList<>();
					for (JsonNode img : resp.get("images")) {
						naverUrls.add(img.get("url").asText());
					}
					if (!naverUrls.isEmpty()) {
						log.info("[D092][스토어] 네이버 이미지 업로드 {}개 완료", naverUrls.size());
						return naverUrls;
					}
				}
			}
		} catch (Exception e) {
			log.warn("[D092][스토어] 네이버 이미지 업로드 실패 — 외부 URL 폴백: {}", e.getMessage());
		}
		return hostedImages;
	}

	@SuppressWarnings("unchecked")
	private void applyUploadedImages(Map<String, Object> requestBody, Product product) {
		List<String> hosted = product.getHostedImages();
		if (hosted == null || hosted.isEmpty()) {
			return;
		}
		Object origin = requestBody.get("originProduct");
		if (!(origin instanceof Map)) {
			return;
		}
		List<String> capped = hosted.subList(0, Math.min(hosted.size(), MAX_PUBLISH_IMAGES));
		List<String> targetImages = uploadImagesToNaver(capped).stream()
			.map(this::ensureImageExtension).toList();
		applyImages((Map<String, Object>)origin, targetImages);
	}

	private byte[] downloadImage(String imageUrl) {
		try (InputStream is = new URL(imageUrl).openStream()) {
			return is.readAllBytes();
		} catch (Exception e) {
			log.error("[D092][스토어] 이미지 다운로드 실패: {} - {}", imageUrl, e.getMessage());
			return null;
		}
	}

	private String ensureImageExtension(String url) {
		if (url == null || url.isBlank()) {
			return url;
		}
		String low = url.toLowerCase();
		if (!low.contains(".jpg") && !low.contains(".jpeg") && !low.contains(".png") && !low.contains(".gif")) {
			return url + (url.contains("?") ? "&" : "?") + "f=.jpg";
		}
		return url;
	}

	@SuppressWarnings("unchecked")
	private void applyImages(Map<String, Object> originProduct, List<String> hostedImages) {
		Object existing = originProduct.get("images");
		Map<String, Object> images = (existing instanceof Map)
			? (Map<String, Object>)existing
			: new HashMap<>();

		images.put("representativeImage", Map.of("url", hostedImages.get(0)));
		if (hostedImages.size() > 1) {
			List<Map<String, Object>> optionalImages = hostedImages.subList(1, hostedImages.size()).stream()
				.map(u -> Map.<String, Object>of("url", u))
				.toList();
			images.put("optionalImages", optionalImages);
		} else {
			images.remove("optionalImages");
		}

		originProduct.put("images", images);
	}

	private String pickChannelProductNo(JsonNode channelProducts) {
		JsonNode chosen = pickChannelProduct(channelProducts);
		if (chosen != null) {
			String ch = chosen.path("channelProductNo").asText("");
			if (!ch.isBlank()) {
				return ch;
			}
		}
		return null;
	}

	private JsonNode pickChannelProduct(JsonNode channelProducts) {
		JsonNode chosen = null;
		for (JsonNode cp : channelProducts) {
			if ("STOREFARM".equals(cp.path("channelServiceType").asText())) {
				return cp;
			}
			if (chosen == null) {
				chosen = cp;
			}
		}
		return chosen;
	}
}
