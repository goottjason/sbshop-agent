package com.sbshop.agent.infrastructure.client.coupang.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalResult;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPrice;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPriceMiss;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketLiveOption;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.dto.CategoryMetaResult;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangMarketClient implements MarketClient {

	private static final Set<String> PLACEHOLDER_ATTRIBUTE_VALUES = Set.of("수량", "용량", "중량", "정", "개", "캡슐");

	private static final String CATALOG_BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
	private static final String VENDOR_ITEM_BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/";
	private static final Set<String> OPTION_ABSENT_MESSAGES = Set.of("유효한 옵션이 없습니다",
		"유효하지 않은 ID가 입력되었습니다");
	private static final Set<String> APPROVAL_ELIGIBLE_STATUSES = Set.of("임시저장", "승인반려", "부분승인완료");
	private static final String APPROVAL_RETRY_MARKER = "등록/수정 중";
	private static final String APPROVAL_RETRY_NOTE = "쿠팡이 이 상품을 등록/수정 중입니다 — 10분 뒤 같은 ID로 다시 요청하세요(자동 재시도 없음)";
	private static final int CATALOG_PAGE_SIZE = 100;
	private static final int CATALOG_MAX_PAGES = 1000;

	private final CoupangProperties properties;
	private final ObjectMapper objectMapper;
	private final CoupangRestClient restClient;
	private final CoupangCategoryPredictor categoryPredictor;
	private final CoupangProductParser productParser;
	private final CoupangSearchTagGenerator searchTagGenerator;
	private final CoupangDataMapper dataMapper;
	private final CoupangMetaService metaService;
	private final CoupangAttributeValueResolver attributeValueResolver;

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.COUPANG;
	}

	@Override
	public Map<String, String> publish(Product product) {
		return publish(product, MarketPublishContext.empty());
	}

	@Override
	public Map<String, String> publish(Product product, MarketPublishContext context) {
		log.info("[쿠팡] 상품 등록 파이프라인 가동 - SKU: {}", product.getSbCode());
		try {
			Long categoryId = resolveCategoryId(product, context);
			CategoryMetaResult metaResult = metaService.getCategoryMeta(categoryId, product);
			List<String> tags = context.keywords().isEmpty()
				? searchTagGenerator.generateTags(product) : context.keywords();
			int salePrice = context.salePrice() != null
				? context.salePrice().intValue()
				: (product.getSalePrice() != null ? product.getSalePrice().intValue() : 0);

			List<String> hostedUrls = product.getHostedImages();
			List<CoupangProductPayload.Item.Image> images = IntStream.range(0, hostedUrls.size())
				.mapToObj(i -> CoupangProductPayload.Item.Image.builder()
					.imageOrder(i)
					.imageType(i == 0 ? "REPRESENTATION" : "DETAIL")
					.vendorPath(hostedUrls.get(i))
					.build())
				.toList();

			CoupangProductPayload payload = CoupangProductPayload.create(
				product, categoryId,
				product.getBaseName(), product.getBaseName(), product.getBrand(),
				salePrice,
				tags, images, metaResult.notices(), metaResult.attributes(),
				product.getDetailHtml(), shippingAccount(context));

			String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
			String responseJson = restClient.requestWithBody("POST", path, payload);

			JsonNode root = objectMapper.readTree(responseJson);
			if (root.path("data").isNull() || root.path("data").asText("").isEmpty()) {
				throw new RuntimeException("쿠팡 등록 실패: " + root.path("message").asText());
			}
			String sellerProductId = root.path("data").asText();
			log.info("[쿠팡] 상품 등록 성공! sellerProductId: {}", sellerProductId);

			Map<String, String> identifiers = new HashMap<>();
			identifiers.put("sellerProductId", sellerProductId);
			return identifiers;
		} catch (Exception e) {
			log.error("[쿠팡] 연동 실패: {}", e.getMessage());
			throw new RuntimeException("쿠팡 연동 오류", e);
		}
	}

	@Override
	public com.sbshop.agent.core.domain.market.MarketPresence checkPresence(String marketItemId) {
		String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + marketItemId;
		String responseJson;
		try {
			responseJson = restClient.get(path);
		} catch (Exception e) {
			return com.sbshop.agent.core.domain.market.MarketFailureClassifier.indicatesDeleted(e)
				? com.sbshop.agent.core.domain.market.MarketPresence.ABSENT
				: com.sbshop.agent.core.domain.market.MarketPresence.UNKNOWN;
		}
		try {
			JsonNode data = objectMapper.readTree(responseJson).path("data");
			if (data.isMissingNode() || data.isNull()) {
				return com.sbshop.agent.core.domain.market.MarketPresence.ABSENT;
			}
			String statusName = data.path("statusName").asText(null);
			return com.sbshop.agent.core.domain.market.MarketFailureClassifier.indicatesDeletedStatus(statusName)
				? com.sbshop.agent.core.domain.market.MarketPresence.ABSENT
				: com.sbshop.agent.core.domain.market.MarketPresence.PRESENT;
		} catch (Exception e) {
			log.warn("[쿠팡] 존재 판정 파싱 실패 — 미확정 처리: {}, error={}", marketItemId, e.getMessage());
			return com.sbshop.agent.core.domain.market.MarketPresence.UNKNOWN;
		}
	}

	@Override
	public MarketItemInfo extractMarketItem(String marketItemId) {
		String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + marketItemId
			+ "?vendorId=" + properties.getVendorId();
		String responseJson = restClient.get(path);
		try {
			JsonNode dataNode = productParser.parseDataNode(responseJson);
			JsonNode firstItem = productParser.getFirstItem(dataNode);
			return MarketItemInfo.builder()
				.isMasterData(true)
				.name(dataNode.path("displayProductName").asText(null))
				.marketIdentifiers(dataMapper.buildIdentifiers(marketItemId, firstItem))
				.mappingKey(firstItem.path("externalVendorSku").asText(""))
				.brand(dataNode.path("brand").asText(null))
				.manufacturer(dataNode.path("manufacture").asText(null))
				.barcode(firstItem.path("barcode").asText(null))
				.generalProductName(dataNode.path("generalProductName").asText(null))
				.rawData(dataMapper.buildRawData(dataNode))
				.build();
		} catch (Exception e) {
			log.error("[쿠팡] 상품 정보 추출 실패 (ID: {}): {}", marketItemId, e.getMessage());
			throw new RuntimeException("쿠팡 데이터 추출 오류", e);
		}
	}

	@Override
	public MarketItemInfo parseLocalData(Map<String, Object> rawData) {
		if (rawData == null || rawData.isEmpty()) {
			return MarketItemInfo.builder().build();
		}
		String displayProductName = rawData.get("displayProductName") != null
			? String.valueOf(rawData.get("displayProductName")) : null;
		String brand = rawData.get("brand") != null ? String.valueOf(rawData.get("brand")) : null;
		String manufacturer = rawData.get("manufacture") != null ? String.valueOf(rawData.get("manufacture")) : null;
		String generalProductName = rawData.get("generalProductName") != null
			? String.valueOf(rawData.get("generalProductName")) : null;

		String externalVendorSku = "";
		String barcode = null;
		BigDecimal salePrice = null;
		Integer stock = 0;
		try {
			Object itemsObj = rawData.get("items");
			if (itemsObj instanceof List<?> items && !items.isEmpty()) {
				@SuppressWarnings("unchecked") Map<String, Object> firstItem = (Map<String, Object>)items.get(0);
				externalVendorSku = firstItem.get("externalVendorSku") != null
					? String.valueOf(firstItem.get("externalVendorSku")) : "";
				barcode = firstItem.get("barcode") != null ? String.valueOf(firstItem.get("barcode")) : null;
				if (firstItem.get("salePrice") != null)
					salePrice = new BigDecimal(String.valueOf(firstItem.get("salePrice")));
				if (firstItem.get("maximumBuyCount") != null)
					stock = Integer.parseInt(String.valueOf(firstItem.get("maximumBuyCount")));
			}
		} catch (Exception e) {
			log.warn("쿠팡 로컬 데이터 파싱 실패", e);
		}
		return MarketItemInfo.builder()
			.isMasterData(true)
			.name(displayProductName)
			.mappingKey(externalVendorSku)
			.brand(brand)
			.manufacturer(manufacturer)
			.barcode(barcode)
			.generalProductName(generalProductName)
			.salePrice(salePrice)
			.stock(stock)
			.rawData(rawData)
			.build();
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut) {
		if (marketItemId == null || marketItemId.isEmpty()) {
			throw new IllegalStateException("쿠팡 vendorItemId 없음");
		}
		String base = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + marketItemId;
		if (price != null) {
			verifyEnvelopeLenient(restClient.put(base + "/prices/" + price, Map.of()), "[쿠팡] 가격 변경");
		}
		verifyEnvelopeLenient(restClient.put(base + "/quantities/" + quantity, Map.of()), "[쿠팡] 재고 변경");
		verifyEnvelopeLenient(restClient.put(base + (soldOut ? "/sales/stop" : "/sales/resume"), Map.of()),
			"[쿠팡] 판매상태 변경");
		log.info("[쿠팡] 가격/재고/판매상태: vendorItemId={}, price={}, qty={}, soldOut={}",
			marketItemId, price, quantity, soldOut);
		return currentRawData;
	}

	@Override
	public boolean syncBarcode(Product product, String marketItemId, Map<String, Object> currentRawData) {
		String barcode = (product.getProductSpec() == null) ? null : product.getProductSpec().getBarcode();
		if (barcode == null || barcode.isBlank()) {
			log.info("[쿠팡] 바코드 없음 — 전송 생략: {}", marketItemId);
			return false;
		}
		if (marketItemId == null || marketItemId.isBlank()) {
			throw new IllegalStateException("쿠팡 sellerProductId 없음 — 바코드 전송 불가");
		}
		String base = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
		Map<String, Object> rawData;
		try {
			JsonNode root = objectMapper.readTree(restClient.get(base + "/" + marketItemId));
			rawData = objectMapper.convertValue(root.path("data"),
				new TypeReference<Map<String, Object>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("쿠팡 상품 조회 실패 — 바코드 전송 중단: " + marketItemId, e);
		}
		if (rawData == null || rawData.isEmpty()) {
			throw new IllegalStateException("쿠팡 상품 조회 응답에 data 없음: " + marketItemId);
		}
		@SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>)rawData
			.get("items");
		if (items == null || items.isEmpty()) {
			throw new IllegalStateException("쿠팡 상품에 items 없음 — 바코드 전송 불가: " + marketItemId);
		}
		if (allItemsHaveBarcode(items, barcode)) {
			log.info("[쿠팡] 바코드가 이미 마켓과 같다 — PUT 생략(심사중 전환 방지): {} barcode={}",
				marketItemId, barcode);
			return false;
		}
		for (Map<String, Object> item : items) {
			item.put("barcode", barcode);
			item.put("emptyBarcode", false);
			item.put("emptyBarcodeReason", null);
		}
		sanitizeItemAttributes(items, product, rawData);
		rawData.put("requested", true);
		verifyEnvelopeStrict(restClient.put(base, rawData), "[쿠팡] 바코드 전송");
		log.info("[쿠팡] 바코드 전송 완료(심사중 전환): {} barcode={}", marketItemId, barcode);
		return true;
	}

	private static boolean allItemsHaveBarcode(List<Map<String, Object>> items, String barcode) {
		for (Map<String, Object> item : items) {
			Object current = item.get("barcode");
			if (current == null || !barcode.equals(String.valueOf(current).trim())) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(Product product,
		String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		if (marketItemId == null || marketItemId.isBlank()) {
			throw new IllegalStateException("쿠팡 sellerProductId(marketItemId) 없음 — 이미지 재게시 불가");
		}
		Map<String, Object> rawData;
		String getPath = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + marketItemId;
		String responseJson = restClient.get(getPath);
		log.info("[D092][쿠팡] seller-products GET (len={}): {}", responseJson == null ? -1 : responseJson.length(),
			responseJson == null ? "null" : responseJson.substring(0, Math.min(responseJson.length(), 3000)));
		try {
			JsonNode root = objectMapper.readTree(responseJson);
			rawData = objectMapper.convertValue(root.path("data"),
				new TypeReference<Map<String, Object>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("쿠팡 상품 조회 응답 파싱 실패 (ID: " + marketItemId + ")", e);
		}
		if (rawData == null || rawData.isEmpty()) {
			throw new IllegalStateException("쿠팡 상품 전체 페이로드 조회 실패 — data 없음 (ID: " + marketItemId + ")");
		}

		@SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>)rawData
			.get("items");
		if (items != null && !items.isEmpty()) {
			Map<String, Object> firstItem = items.get(0);
			List<Map<String, Object>> coupangImages = new ArrayList<>();
			for (int i = 0; i < hostedImages.size(); i++) {
				Map<String, Object> imgMap = new HashMap<>();
				imgMap.put("imageOrder", i);
				imgMap.put("imageType", i == 0 ? "REPRESENTATION" : "DETAIL");
				imgMap.put("vendorPath", hostedImages.get(i));
				coupangImages.add(imgMap);
			}
			firstItem.put("images", coupangImages);

			List<Map<String, Object>> contents = new ArrayList<>();
			Map<String, Object> contentMap = new HashMap<>();
			contentMap.put("contentsType", "HTML");
			contentMap.put("contentDetails", List.of(Map.of("content", newDetailHtml, "detailType", "TEXT")));
			contents.add(contentMap);
			firstItem.put("contents", contents);
		}

		sanitizeItemAttributes(items, product, rawData);

		String base = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
		rawData.put("requested", true);
		try {
			String putBody = objectMapper.writeValueAsString(rawData);
			log.info("[D092][쿠팡] 상품수정 PUT body (len={}): {}", putBody.length(),
				putBody.substring(0, Math.min(putBody.length(), 3000)));
		} catch (Exception ignore) {}
		String putResp = restClient.put(base, rawData);
		log.info("[D092][쿠팡] 상품수정 PUT resp: {}", putResp);
		verifyEnvelopeStrict(putResp, "[쿠팡] 상품수정(이미지/상세)");
		log.info("[쿠팡] 이미지/HTML 동기화 완료(requested=true 자동승인요청): {}", marketItemId);
		return rawData;
	}

	@Override
	public List<MarketCatalogEntry> fetchCatalog(long throttleMs) {
		List<MarketCatalogEntry> entries = new ArrayList<>();
		String vendorId = restClient.resolveVendorId();
		String nextToken = "";
		boolean reachedLastPage = false;
		for (int page = 0; page < CATALOG_MAX_PAGES; page++) {
			String path = CATALOG_BASE + "?vendorId=" + vendorId
				+ "&maxPerPage=" + CATALOG_PAGE_SIZE + "&nextToken=" + nextToken;
			String response;
			try {
				response = restClient.get(path);
			} catch (Exception e) {
				throw new RuntimeException("[쿠팡] 전체 상품 조회 실패 (누적 " + entries.size() + "건, nextToken="
					+ nextToken + "): " + e.getMessage(), e);
			}
			verifyEnvelopeStrict(response, "[쿠팡] 전체 상품 조회");
			JsonNode root = readEnvelope(response);
			for (JsonNode item : root.path("data")) {
				MarketCatalogEntry entry = toCatalogEntry(item, null);
				if (entry != null) {
					entries.add(entry);
				}
			}
			nextToken = root.path("nextToken").asText("");
			log.info("[쿠팡] 카탈로그 스캔 누적 {}건 (nextToken={})", entries.size(), nextToken);
			if (nextToken.isBlank()) {
				reachedLastPage = true;
				break;
			}
			sleepQuietly(throttleMs);
		}
		if (!reachedLastPage) {
			throw new RuntimeException("[쿠팡] 전체 상품 조회 실패 — 페이지 상한(" + CATALOG_MAX_PAGES
				+ ")을 소진했는데 nextToken이 비지 않았다 (누적 " + entries.size()
				+ "건). 잘린 카탈로그는 대조에서 '마켓에 없는 상품'으로 오독되므로 반환하지 않는다.");
		}
		return entries;
	}

	@Override
	public boolean supportsLiveOptionLookup() {
		return true;
	}

	@Override
	public Optional<MarketLiveOption> fetchLiveOption(String optionId) {
		if (optionId == null || optionId.isBlank()) {
			return Optional.empty();
		}
		String id = optionId.trim();
		String response;
		try {
			response = restClient.get(VENDOR_ITEM_BASE + id + "/inventories");
		} catch (RuntimeException e) {
			if (isNotFound(e) || isOptionAbsent(e)) {
				log.info("[쿠팡] 옵션 부재: vendorItemId={}", id);
				return Optional.empty();
			}
			throw new RuntimeException("[쿠팡] 옵션 실판매 조회 실패 (vendorItemId=" + id + "): " + e.getMessage(), e);
		}
		verifyEnvelopeStrict(response, "[쿠팡] 옵션 실판매 조회");
		JsonNode data = readEnvelope(response).path("data");
		if (data.isMissingNode() || data.isNull() || !data.isObject()) {
			throw new RuntimeException("[쿠팡] 옵션 실판매 조회 실패 — 데이터 없음 (vendorItemId=" + id + "): "
				+ envelopeSnippet(response));
		}
		String sellerItemId = text(data, "sellerItemId");
		return Optional.of(new MarketLiveOption(sellerItemId.isEmpty() ? id : sellerItemId,
			intOrNull(data, "salePrice"), intOrNull(data, "amountInStock"), boolOrNull(data, "onSale")));
	}

	@Override
	public MarketDraftPrice fetchDraftSalePrice(String marketItemId) {
		if (marketItemId == null || marketItemId.isBlank()) {
			return MarketDraftPrice.missing(MarketDraftPriceMiss.NO_SELLER_PRODUCT_ID);
		}
		String id = marketItemId.trim();
		String response;
		try {
			response = restClient.get(CATALOG_BASE + "/" + id);
		} catch (RuntimeException e) {
			if (isNotFound(e)) {
				return MarketDraftPrice.missing(MarketDraftPriceMiss.PRODUCT_ABSENT);
			}
			throw new RuntimeException("[쿠팡] 등록상품 초안가 조회 실패 (sellerProductId=" + id + "): "
				+ e.getMessage(), e);
		}
		verifyEnvelopeStrict(response, "[쿠팡] 등록상품 초안가 조회");
		JsonNode items = readEnvelope(response).path("data").path("items");
		if (!items.isArray()) {
			log.warn("[쿠팡] 초안가 미상 — 응답에 items 배열이 없다 (sellerProductId={}): {}",
				id, envelopeSnippet(response));
			return MarketDraftPrice.missing(MarketDraftPriceMiss.NO_ITEMS_FIELD);
		}
		if (items.isEmpty()) {
			log.warn("[쿠팡] 초안가 미상 — items 가 빈 배열이다 (sellerProductId={})", id);
			return MarketDraftPrice.missing(MarketDraftPriceMiss.EMPTY_ITEMS);
		}
		Integer salePrice = intOrNull(items.get(0), "salePrice");
		if (salePrice == null) {
			log.warn("[쿠팡] 초안가 미상 — items[0].salePrice 를 읽을 수 없다 (sellerProductId={})", id);
			return MarketDraftPrice.missing(MarketDraftPriceMiss.NO_PRICE_FIELD);
		}
		return MarketDraftPrice.of(salePrice);
	}

	@Override
	public boolean supportsApprovalRequest() {
		return true;
	}

	@Override
	public MarketApprovalResult requestApproval(String marketItemId) {
		if (marketItemId == null || marketItemId.isBlank()) {
			return MarketApprovalResult.failed(marketItemId, null, false, null, null,
				"sellerProductId 가 비어 있습니다 — 호출하지 않았습니다");
		}
		String id = marketItemId.trim();
		String statusName;
		try {
			String statusResponse = restClient.get(CATALOG_BASE + "/" + id);
			verifyEnvelopeStrict(statusResponse, "[쿠팡] 승인요청 전 상태 조회");
			statusName = text(readEnvelope(statusResponse).path("data"), "statusName");
		} catch (RuntimeException e) {
			if (isNotFound(e)) {
				log.info("[쿠팡] 승인요청 건너뜀 — 쿠팡에 없는 상품: sellerProductId={}", id);
				return MarketApprovalResult.skipped(id, null, "쿠팡에 없는 상품입니다(404) — 호출하지 않았습니다");
			}
			log.warn("[쿠팡] 승인요청 전 상태 조회 실패: sellerProductId={}, msg={}", id, e.getMessage());
			return MarketApprovalResult.failed(id, null, false, null, exceptionMessage(e),
				"승인요청 전 상태 조회에 실패해 호출하지 않았습니다");
		}
		if (statusName.isEmpty()) {
			log.warn("[쿠팡] 승인요청 건너뜀 — statusName 부재: sellerProductId={}", id);
			return MarketApprovalResult.skipped(id, null, "상태를 확인하지 못했습니다(statusName 부재) — 호출하지 않았습니다");
		}
		if (!APPROVAL_ELIGIBLE_STATUSES.contains(statusName)) {
			log.info("[쿠팡] 승인요청 건너뜀 — 대상 상태 아님: sellerProductId={}, statusName={}", id, statusName);
			return MarketApprovalResult.skipped(id, statusName,
				"'" + statusName + "' 은 승인 요청 대상이 아닙니다(대상: 임시저장·승인반려·부분승인완료) — 호출하지 않았습니다");
		}

		String response;
		try {
			response = restClient.requestWithBody("PUT", CATALOG_BASE + "/" + id + "/approvals", null);
		} catch (RuntimeException e) {
			String failureMessage = exceptionMessage(e);
			if (failureMessage.contains(APPROVAL_RETRY_MARKER)) {
				log.info("[쿠팡] 승인요청 재시도 대상: sellerProductId={}, msg={}", id, failureMessage);
				return MarketApprovalResult.retryable(id, statusName, null, failureMessage, APPROVAL_RETRY_NOTE);
			}
			log.warn("[쿠팡] 승인요청 실패: sellerProductId={}, msg={}", id, failureMessage);
			return MarketApprovalResult.failed(id, statusName, true, null, failureMessage, "승인 요청 호출이 실패했습니다");
		}

		JsonNode root = readEnvelope(response);
		String code = root == null ? "" : root.path("code").asText("");
		String message = root == null ? envelopeSnippet(response) : root.path("message").asText("");
		if ("SUCCESS".equalsIgnoreCase(code) || "200".equals(code)) {
			log.info("[쿠팡] 승인요청 성공: sellerProductId={}, 이전상태={}", id, statusName);
			return MarketApprovalResult.requested(id, statusName, code, message);
		}
		if (message.contains(APPROVAL_RETRY_MARKER)) {
			log.info("[쿠팡] 승인요청 재시도 대상: sellerProductId={}, msg={}", id, message);
			return MarketApprovalResult.retryable(id, statusName, code.isEmpty() ? null : code, message,
				APPROVAL_RETRY_NOTE);
		}
		log.warn("[쿠팡] 승인요청 실패 — 성공 봉투 아님: sellerProductId={}, code={}, msg={}", id, code, message);
		return MarketApprovalResult.failed(id, statusName, true, code.isEmpty() ? null : code,
			message.isEmpty() ? envelopeSnippet(response) : message,
			"성공 봉투(code=SUCCESS·200)가 아닙니다");
	}

	private static String exceptionMessage(RuntimeException e) {
		StringBuilder joined = new StringBuilder();
		for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
			if (t.getMessage() == null) {
				continue;
			}
			if (joined.length() > 0) {
				joined.append(" / ");
			}
			joined.append(t.getMessage());
		}
		return joined.toString();
	}

	private static Integer intOrNull(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (value.isNumber()) {
			return value.asInt();
		}
		try {
			return Integer.valueOf(value.asText("").trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Boolean boolOrNull(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asBoolean();
	}

	private static boolean isOptionAbsent(RuntimeException e) {
		for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
			String message = t.getMessage();
			if (message == null || !message.contains("400")) {
				continue;
			}
			for (String marker : OPTION_ABSENT_MESSAGES) {
				if (message.contains(marker)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean supportsSingleLookup() {
		return true;
	}

	@Override
	public Optional<MarketCatalogEntry> fetchBySellerCode(String sellerCode) {
		if (sellerCode == null || sellerCode.isBlank()) {
			return Optional.empty();
		}
		String code = sellerCode.trim();
		String response;
		try {
			response = restClient.get(CATALOG_BASE + "/external-vendor-sku-codes/" + code);
		} catch (RuntimeException e) {
			if (isNotFound(e)) {
				log.info("[쿠팡] SB코드 미등록: externalVendorSku={}", code);
				return Optional.empty();
			}
			throw new RuntimeException("[쿠팡] SB코드 단건 조회 실패 (externalVendorSku=" + code + "): "
				+ e.getMessage(), e);
		}
		verifyEnvelopeStrict(response, "[쿠팡] SB코드 단건 조회");
		JsonNode data = readEnvelope(response).path("data");
		JsonNode item = data.isArray() ? (data.isEmpty() ? null : data.get(0)) : (data.isObject() ? data : null);
		if (item == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(toCatalogEntry(item, code));
	}

	private MarketCatalogEntry toCatalogEntry(JsonNode item, String sellerCode) {
		String sellerProductId = text(item, "sellerProductId");
		if (sellerProductId.isEmpty()) {
			return null;
		}
		Map<String, String> identifiers = new LinkedHashMap<>();
		identifiers.put("sellerProductId", sellerProductId);
		putIfPresent(identifiers, item, "productId");
		putIfPresent(identifiers, item, "vendorItemId");
		String status = text(item, "statusName");
		return new MarketCatalogEntry(sellerCode, identifiers, status.isEmpty() ? null : status);
	}

	private static void putIfPresent(Map<String, String> identifiers, JsonNode item, String field) {
		String value = text(item, field);
		if (!value.isEmpty() && !"0".equals(value)) {
			identifiers.put(field, value);
		}
	}

	private static String text(JsonNode node, String field) {
		String value = node.path(field).asText("");
		return value == null ? "" : value.trim();
	}

	private static boolean isNotFound(RuntimeException e) {
		for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
			String message = t.getMessage();
			if (message != null && (message.contains("404") || message.contains("NOT_FOUND"))) {
				return true;
			}
		}
		return false;
	}

	private void sleepQuietly(long throttleMs) {
		if (throttleMs <= 0) {
			return;
		}
		try {
			Thread.sleep(throttleMs);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("[쿠팡] 전체 상품 조회 실패 — 중단됨", ie);
		}
	}

	@Override
	public Optional<String> fetchLinkIdentifier(String sourceIdentifier) {
		return fetchProductId(sourceIdentifier);
	}

	public Optional<String> fetchProductId(String sellerProductId) {
		if (sellerProductId == null || sellerProductId.isBlank()) {
			return Optional.empty();
		}
		try {
			String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + sellerProductId;
			String resp = restClient.get(path);
			JsonNode root = objectMapper.readTree(resp);
			JsonNode productIdNode = root.path("data").path("productId");
			if (productIdNode.isMissingNode() || productIdNode.isNull() || productIdNode.asLong(0L) <= 0L) {
				return Optional.empty();
			}
			String productId = productIdNode.asText();
			if (productId == null || productId.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(productId);
		} catch (Exception e) {
			log.warn("[쿠팡] productId 조회 실패: {}", e.getMessage());
			return Optional.empty();
		}
	}

	@Override
	public void deleteFromMarket(String marketItemId) {
		if (marketItemId == null || marketItemId.isBlank()) {
			throw new IllegalArgumentException("쿠팡 sellerProductId 없음 — 삭제 불가");
		}
		String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + marketItemId;
		try {
			verifyEnvelopeLenient(restClient.requestWithBody("DELETE", path, null), "[쿠팡] 상품 삭제");
			log.info("[쿠팡] 상품 삭제 성공: sellerProductId={}", marketItemId);
		} catch (Exception e) {
			log.error("[쿠팡] 상품 삭제 실패: sellerProductId={}, msg={}", marketItemId, e.getMessage());
			throw e;
		}
	}

	private void sanitizeItemAttributes(List<Map<String, Object>> items, Product product,
		Map<String, Object> rawData) {
		if (items == null) {
			return;
		}
		Map<String, List<String>> unitsByTypeName = null;
		for (Map<String, Object> item : items) {
			if (!(item.get("attributes") instanceof List<?> attributes)) {
				continue;
			}
			List<Map<String, Object>> sanitized = new ArrayList<>();
			for (Object element : attributes) {
				if (!(element instanceof Map<?, ?> attribute)) {
					continue;
				}
				Map<String, Object> entry = new LinkedHashMap<>();
				attribute.forEach((key, value) -> entry.put(String.valueOf(key), value));
				String typeName = attributeText(entry.get("attributeTypeName"));
				String valueName = attributeText(entry.get("attributeValueName"));
				if (valueName.isBlank() && !isExposedAttribute(entry)) {
					continue;
				}
				if (!valueName.isBlank() && !isPlaceholderAttributeValue(typeName, valueName)) {
					sanitized.add(entry);
					continue;
				}
				if (!attributeValueResolver.supportsUnitFamily(typeName, product)) {
					continue;
				}
				if (unitsByTypeName == null) {
					unitsByTypeName = loadUsableUnits(rawData);
				}
				String refilled = attributeValueResolver.resolve(typeName, product, unitsByTypeName.get(typeName));
				if (refilled == null) {
					continue;
				}
				entry.put("attributeValueName", refilled);
				sanitized.add(entry);
			}
			item.put("attributes", sanitized);
		}
	}

	private String attributeText(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private boolean isExposedAttribute(Map<String, Object> entry) {
		return "EXPOSED".equals(attributeText(entry.get("exposed")));
	}

	private boolean isPlaceholderAttributeValue(String typeName, String valueName) {
		return valueName.equals(typeName) || PLACEHOLDER_ATTRIBUTE_VALUES.contains(valueName);
	}

	private Map<String, List<String>> loadUsableUnits(Map<String, Object> rawData) {
		Long categoryCode = displayCategoryCode(rawData);
		if (categoryCode == null) {
			return Map.of();
		}
		try {
			return metaService.getUsableUnits(categoryCode);
		} catch (Exception e) {
			log.warn("[쿠팡] 카테고리 메타 조회 실패 — 고정 단위로 속성 재충전: categoryCode={}, msg={}",
				categoryCode, e.getMessage());
			return Map.of();
		}
	}

	private Long displayCategoryCode(Map<String, Object> rawData) {
		Object value = rawData == null ? null : rawData.get("displayCategoryCode");
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void verifyEnvelopeStrict(String responseJson, String context) {
		verifyEnvelope(responseJson, context, true);
	}

	private void verifyEnvelopeLenient(String responseJson, String context) {
		verifyEnvelope(responseJson, context, false);
	}

	private void verifyEnvelope(String responseJson, String context, boolean strict) {
		JsonNode root = readEnvelope(responseJson);
		String code = root == null ? "" : root.path("code").asText("");
		if (code.isBlank()) {
			if (strict) {
				throw new RuntimeException(context + " 실패 — 응답 봉투를 확인할 수 없음: " + envelopeSnippet(responseJson));
			}
			return;
		}
		if ("SUCCESS".equalsIgnoreCase(code) || "200".equals(code)) {
			return;
		}
		throw new RuntimeException(context + " 실패 — code=" + code
			+ ", message=" + root.path("message").asText("") + ", 응답=" + envelopeSnippet(responseJson));
	}

	private JsonNode readEnvelope(String responseJson) {
		if (responseJson == null || responseJson.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(responseJson);
		} catch (Exception e) {
			return null;
		}
	}

	private static String envelopeSnippet(String responseJson) {
		if (responseJson == null) {
			return "null";
		}
		return responseJson.substring(0, Math.min(responseJson.length(), 500));
	}

	private Long resolveCategoryId(Product product, MarketPublishContext context) {
		if (context.hasCategory()) {
			try {
				return Long.parseLong(context.categoryId().trim());
			} catch (NumberFormatException e) {
				log.warn("[쿠팡] 검수 카테고리가 숫자가 아니라 자동 예측으로 폴백: {}", context.categoryId());
			}
		}
		return categoryPredictor.predictCategory(product);
	}

	private CoupangProductPayload.ShippingAccount shippingAccount(MarketPublishContext context) {
		CoupangProductPayload.ShippingAccount base = CoupangProductPayload.ShippingAccount.legacyDefaults();
		String outbound = context.extraString("outboundShippingPlaceCode");
		String returnCenter = context.extraString("returnCenterCode");
		if (outbound == null && returnCenter == null) {
			return base;
		}
		Integer outboundCode = base.outboundShippingPlaceCode();
		if (outbound != null) {
			try {
				outboundCode = Integer.parseInt(outbound.trim());
			} catch (NumberFormatException e) {
				log.warn("[쿠팡] 출고지 코드가 숫자가 아님 — 기본값 사용: {}", outbound);
			}
		}
		return CoupangProductPayload.ShippingAccount.builder()
			.vendorId(base.vendorId())
			.vendorUserId(base.vendorUserId())
			.outboundShippingPlaceCode(outboundCode)
			.returnCenterCode(returnCenter != null ? returnCenter : base.returnCenterCode())
			.returnChargeName(base.returnChargeName())
			.companyContactNumber(base.companyContactNumber())
			.returnZipCode(base.returnZipCode())
			.returnAddress(base.returnAddress())
			.returnAddressDetail(base.returnAddressDetail())
			.returnCharge(context.extraInt("deliveryChargeOnReturn", base.returnCharge()))
			.build();
	}
}
