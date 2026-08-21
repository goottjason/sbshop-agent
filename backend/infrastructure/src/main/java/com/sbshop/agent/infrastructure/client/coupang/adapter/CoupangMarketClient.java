package com.sbshop.agent.infrastructure.client.coupang.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangMarketClient implements MarketClient {

	private final CoupangProperties properties;
	private final ObjectMapper objectMapper;
	private final CoupangRestClient restClient;
	private final CoupangCategoryPredictor categoryPredictor;
	private final CoupangProductParser productParser;
	private final CoupangSearchTagGenerator searchTagGenerator;
	private final CoupangDataMapper dataMapper;
	private final CoupangMetaService metaService;

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
	public Map<String, Object> syncImagesAndHtml(Product product,
		String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		if (marketItemId == null || marketItemId.isBlank()) {
			throw new IllegalStateException("쿠팡 sellerProductId(marketItemId) 없음 — 이미지 재게시 불가");
		}
		Map<String, Object> rawData = currentRawData;
		if (rawData == null || !rawData.containsKey("items")) {
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
