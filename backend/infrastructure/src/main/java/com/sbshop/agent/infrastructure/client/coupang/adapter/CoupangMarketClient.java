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
		Integer price, Integer stock) {
		// 원본(seller-product) 페이로드가 없으면 실 반영 불가 → 예외를 전파해 상위에서 '실패 마켓'으로 표면화.
		if (currentRawData == null || !currentRawData.containsKey("items")) {
			throw new IllegalStateException("쿠팡 원본 데이터(items)가 없어 가격/재고를 반영할 수 없습니다: " + marketItemId);
		}
		@SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>)currentRawData
			.get("items");
		if (items == null || items.isEmpty()) {
			throw new IllegalStateException("쿠팡 원본 데이터(items 비어있음): " + marketItemId);
		}
		Map<String, Object> firstItem = items.get(0);
		if (price != null)
			firstItem.put("salePrice", price);
		if (stock != null)
			firstItem.put("maximumBuyCount", stock);
		// syncImagesAndHtml과 동일하게 전체 seller-product 페이로드를 PUT (dev 검증된 갱신 패턴).
		restClient.put("/v2/providers/seller_api/apis/api/v1/marketplace/seller-products", currentRawData);
		log.info("[쿠팡] 가격/재고 동기화 완료: itemId={}, price={}, stock={}", marketItemId, price, stock);
		return currentRawData;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		if (currentRawData == null || !currentRawData.containsKey("items"))
			return currentRawData;
		try {
			@SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>)currentRawData
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
			String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
			restClient.put(path, currentRawData);
			log.info("[쿠팡] 이미지/HTML 동기화 완료: {}", marketItemId);
		} catch (Exception e) {
			log.error("[쿠팡] 이미지/HTML 동기화 실패", e);
		}
		return currentRawData;
	}
}
