package com.sbshop.agent.infrastructure.client.coupang.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.COUPANG;
	}

	@Override
	public Map<String, String> publish(Product product) {
		log.info("[쿠팡] 상품 등록 시작: {}", product.getSbCode());
		// TODO: Phase 5에서 CoupangCategoryPredictor, CoupangMetaService 등과 함께 구현
		Map<String, String> identifiers = new HashMap<>();
		identifiers.put("sellerProductId", "CPG-" + product.getSbCode());
		return identifiers;
	}

	@Override
	public MarketItemInfo extractMarketItem(String marketItemId) {
		String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + marketItemId
				+ "?vendorId=" + properties.getVendorId();
		String responseJson = restClient.get(path);
		try {
			JsonNode dataNode = objectMapper.readTree(responseJson).path("data");
			return MarketItemInfo.builder()
					.isMasterData(true)
					.name(dataNode.path("displayProductName").asText(null))
					.mappingKey(dataNode.path("items").path(0).path("externalVendorSku").asText(""))
					.rawData(objectMapper.convertValue(dataNode, Map.class))
					.build();
		} catch (Exception e) {
			log.error("쿠팡 상품 정보 추출 실패 (ID: {}): {}", marketItemId, e.getMessage());
			throw new RuntimeException("쿠팡 데이터 추출 오류", e);
		}
	}

	@Override
	public MarketItemInfo parseLocalData(Map<String, Object> rawData) {
		if (rawData == null || rawData.isEmpty()) {
			return MarketItemInfo.builder().build();
		}
		return MarketItemInfo.builder()
				.isMasterData(true)
				.name(rawData.get("displayProductName") != null ? String.valueOf(rawData.get("displayProductName")) : null)
				.brand(rawData.get("brand") != null ? String.valueOf(rawData.get("brand")) : null)
				.rawData(rawData)
				.build();
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
			Integer price, Integer stock) {
		try {
			if (currentRawData != null && currentRawData.containsKey("items")) {
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> items = (List<Map<String, Object>>) currentRawData.get("items");
				if (items != null && !items.isEmpty()) {
					Map<String, Object> firstItem = items.get(0);
					if (price != null) firstItem.put("salePrice", price);
					if (stock != null) firstItem.put("maximumBuyCount", stock);
				}
			}
		} catch (Exception e) {
			log.warn("쿠팡 로컬 Map 데이터 패치 중 오류", e);
		}
		return currentRawData;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
			List<String> hostedImages, String newDetailHtml) {
		if (currentRawData == null || !currentRawData.containsKey("items")) return currentRawData;
		try {
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> items = (List<Map<String, Object>>) currentRawData.get("items");
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
			log.error("쿠팡 이미지/HTML 동기화 실패", e);
		}
		return currentRawData;
	}
}
