package com.sbshop.agent.infrastructure.client.coupang.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
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
		log.info("[쿠팡] 상품 등록 파이프라인 가동 - SKU: {}", product.getSbCode());
		try {
			Long categoryId = categoryPredictor.predictCategory(product);
			CategoryMetaResult metaResult = metaService.getCategoryMeta(categoryId, product);
			List<String> tags = searchTagGenerator.generateTags(product);

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
				product.getSalePrice() != null ? product.getSalePrice().intValue() : 0,
				tags, images, metaResult.notices(), metaResult.attributes(),
				product.getDetailHtml());

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
		// 쿠팡은 vendorItemId 단위 전용 엔드포인트로 가격/재고/판매상태를 반영한다(저장된 rawData 불필요).
		// price/quantity는 경로 파라미터, 바디 없음(HMAC는 method+path에 서명).
		if (marketItemId == null || marketItemId.isEmpty()) {
			throw new IllegalStateException("쿠팡 vendorItemId 없음");
		}
		// 자격증명 검증은 CoupangRestClient가 DB 우선(env 폴백)으로 수행 — 미설정 시 명확한 예외 전파.
		String base = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + marketItemId;
		// 값은 경로 파라미터. JDK HttpClient가 무바디 PUT에 Content-Length를 안 보내 Akamai가 411을 반환하므로,
		// Coupang이 무시하는 빈 JSON 객체({})를 바디로 보내 Content-Length를 강제한다.
		if (price != null) {
			restClient.put(base + "/prices/" + price, java.util.Map.of());
		}
		restClient.put(base + "/quantities/" + quantity, java.util.Map.of()); // 항상 ≥1
		// 판매상태: 코드에 없던 신규 경로 — 라이브 검증 필요. 빈 JSON 바디는 기존 411 회피 관습.
		restClient.put(base + (soldOut ? "/sales/stop" : "/sales/resume"), java.util.Map.of());
		log.info("[쿠팡] 가격/재고/판매상태: vendorItemId={}, price={}, qty={}, soldOut={}",
			marketItemId, price, quantity, soldOut);
		return currentRawData;
	}

	@Override
	public void deleteFromMarket(String marketItemId) {
		// 완전 상품 삭제(F-PROD-27/28). 쿠팡 상품 리스팅 삭제는 seller-products/{sellerProductId} DELETE로 수행한다.
		// marketItemId는 발행(publish)이 반환·저장하는 sellerProductId(상품 단위 안정 식별자, extractMarketItem·
		// syncImagesAndHtml이 seller-products 경로에 쓰는 것과 동일). vendorItemId는 가격/재고/판매상태 전용이라
		// 상품 삭제에는 사용하지 않는다. 주문이력 등으로 하드삭제를 거부하면 REST 오류가 예외로 표면화되고,
		// 오케스트레이터가 best-effort로 수집한다.
		if (marketItemId == null || marketItemId.isBlank()) {
			throw new IllegalArgumentException("쿠팡 sellerProductId 없음 — 삭제 불가");
		}
		String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + marketItemId;
		try {
			restClient.delete(path);
			log.info("[쿠팡] 상품 삭제 성공: sellerProductId={}", marketItemId);
		} catch (Exception e) {
			log.error("[쿠팡] 상품 삭제 실패: sellerProductId={}, msg={}", marketItemId, e.getMessage());
			throw e;
		}
	}

	/** 백필용: sourceIdentifier(=sellerProductId)로 링크식별자(productId)를 조회한다. */
	@Override
	public java.util.Optional<String> fetchLinkIdentifier(String sourceIdentifier) {
		return fetchProductId(sourceIdentifier);
	}

	/** seller-products GET에서 공개 productId를 조회한다(상품페이지 링크용). 없으면 Optional.empty(). */
	public java.util.Optional<String> fetchProductId(String sellerProductId) {
		if (sellerProductId == null || sellerProductId.isBlank()) {
			return java.util.Optional.empty();
		}
		try {
			// syncImagesAndHtml 과 동일한 seller-products GET 경로 접두사 재사용.
			String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + sellerProductId;
			String resp = restClient.get(path);
			// 응답은 { code, message, data: { sellerProductId, productId, items:[...], ... } } 형태.
			JsonNode root = objectMapper.readTree(resp);
			JsonNode productIdNode = root.path("data").path("productId");
			if (productIdNode.isMissingNode() || productIdNode.isNull() || productIdNode.asLong(0L) <= 0L) {
				return java.util.Optional.empty();
			}
			String productId = productIdNode.asText();
			if (productId == null || productId.isBlank()) {
				return java.util.Optional.empty();
			}
			return java.util.Optional.of(productId);
		} catch (Exception e) {
			log.warn("[쿠팡] productId 조회 실패: {}", e.getMessage());
			return java.util.Optional.empty();
		}
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		if (marketItemId == null || marketItemId.isBlank()) {
			throw new IllegalStateException("쿠팡 sellerProductId(marketItemId) 없음 — 이미지 재게시 불가");
		}
		// marketItemId 는 등록 식별자에서 온 sellerProductId(권위 있는 상품 식별자)다.
		// 저장된 rawData 가 {}(가격/재고만 있고 items 없음)인 경우가 잦으므로, items 가 없으면
		// 전체 상품 페이로드를 GET 으로 다시 받아 작업 대상 rawData 로 사용한다.
		Map<String, Object> rawData = currentRawData;
		if (rawData == null || !rawData.containsKey("items")) {
			String getPath = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + marketItemId;
			String responseJson = restClient.get(getPath);
			log.info("[D092][쿠팡] seller-products GET (len={}): {}", responseJson == null ? -1 : responseJson.length(),
				responseJson == null ? "null" : responseJson.substring(0, Math.min(responseJson.length(), 3000)));
			try {
				// 응답은 { code, message, data: { sellerProductId, items:[...], ... } } 형태.
				JsonNode root = objectMapper.readTree(responseJson);
				rawData = objectMapper.convertValue(root.path("data"),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
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

		// sellerProductId 는 rawData 값이 아니라 권위 있는 marketItemId 를 사용한다.
		String base = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + marketItemId;
		try {
			String putBody = objectMapper.writeValueAsString(rawData);
			log.info("[D092][쿠팡] 상품수정 PUT body (len={}): {}", putBody.length(),
				putBody.substring(0, Math.min(putBody.length(), 3000)));
		} catch (Exception ignore) { /* 로깅 실패 무시 */ }
		String putResp = restClient.put(base, rawData);
		log.info("[D092][쿠팡] 상품수정 PUT resp: {}", putResp);
		log.info("[쿠팡] 이미지/HTML 동기화 완료: {}", marketItemId);
		// 상품 수정(PUT)만으로는 "임시저장/수정중" 상태에 머문다. 반드시 승인요청을 별도 호출해야 반영된다.
		// (등록 경로는 POST requested(true)로 자동 제출하지만, 수정 경로에는 이 호출이 필요하다.)
		// 무바디 PUT은 JDK HttpClient가 Content-Length를 안 보내 Akamai가 411을 반환한다(같은 파일 가격/재고 관습).
		// 쿠팡이 무시하는 빈 JSON({})을 바디로 보내 Content-Length를 강제한다.
		String approvalResp = restClient.put(base + "/approvals", java.util.Map.of());
		log.info("[D092][쿠팡] 승인요청 resp: {}", approvalResp);
		log.info("[쿠팡] 이미지/HTML 수정 후 승인요청 완료: {}", marketItemId);
		return rawData;
	}
}
