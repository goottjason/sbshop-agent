package com.sbshop.agent.infrastructure.client.smartstore.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.config.MarketRegistrationDefaults;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
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
				Object current = originProduct.get("statusType");
				if (current == null || "SALE".equals(current) || "OUTOFSTOCK".equals(current)) {
					originProduct.put("statusType", "SALE");
				}
			}

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
		if ("OUTOFSTOCK".equals(originProduct.get("statusType"))) {
			originProduct.put("statusType", "SALE");
			log.info("[스토어] statusType=OUTOFSTOCK → SALE 치환 — 조회 전용 파생값이라 "
				+ "그대로도 빈 값으로도 PUT 이 거부된다. 재고 0 이면 네이버가 다시 품절로 표시한다");
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
