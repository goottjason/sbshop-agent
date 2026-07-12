package com.sbshop.agent.infrastructure.client.smartstore.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartstoreMarketClient implements MarketClient {

	private final SmartstoreRestClient restClient;
	private final ObjectMapper objectMapper;

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.SMART_STORE;
	}

	@Override
	public Map<String, String> publish(Product product) {
		log.info("[Smartstore] 상품 등록 시작: {}", product.getSbCode());
		try {
			List<String> hostedImages = product.getHostedImages();

			Map<String, Object> originProduct = new HashMap<>();
			originProduct.put("productName", product.getProductName());
			originProduct.put("salePrice", product.getSalePrice() != null ? product.getSalePrice().intValue() : 0);
			originProduct.put("stockQuantity", product.getStock() != null ? product.getStock() : 0);
			originProduct.put("productCode", product.getSbCode());
			if (product.getDetailHtml() != null) {
				originProduct.put("detailContent", product.getDetailHtml().replace("\"", "\\\"").replace("\n", ""));
			}
			if (!hostedImages.isEmpty()) {
				originProduct.put("representativeImage", hostedImages.get(0));
				if (hostedImages.size() > 1) {
					originProduct.put("optionalImages", hostedImages.subList(1, hostedImages.size()));
				}
			}

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);

			String response = restClient.post("/v2/products", requestBody);
			JsonNode node = objectMapper.readTree(response);
			String originProductNo = node.path("originProduct").path("originProductNo").asText("");

			log.info("[Smartstore] 상품 등록 성공: originProductNo={}", originProductNo);
			Map<String, String> identifiers = new HashMap<>();
			identifiers.put("originProductNo", originProductNo);
			return identifiers;
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
		try {
			String response = restClient.get("/v2/products/origin-products/" + marketItemId);
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			Map<String, Object> originProduct = objectMapper.convertValue(originNode, Map.class);

			if (price != null)
				originProduct.put("salePrice", price);
			if (soldOut) {
				// 스마트스토어: 재고 0이면 API가 자동으로 OUTOFSTOCK(품절) 처리(statusType 무시됨).
				// OUTOFSTOCK은 수정 API에서 직접 지정 불가라 재고 0으로 품절을 표현한다.
				originProduct.put("stockQuantity", 0);
			} else {
				originProduct.put("stockQuantity", quantity);
				// 판매중 복귀: 마켓 잠금상태(SUSPENSION/PROHIBITION)는 보존, 그 외에만 SALE.
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
			throw e; // 실패 표면화(SP-A 원칙)
		} catch (Exception e) {
			log.error("[Smartstore] 가격/재고 업데이트 실패: {}", e.getMessage());
			throw new RuntimeException("[Smartstore] 가격/재고 업데이트 실패", e); // 실패 표면화(SP-A 원칙)
		}
		return currentRawData;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		try {
			String response = restClient.get("/v2/products/origin-products/" + marketItemId);
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			Map<String, Object> originProduct = objectMapper.convertValue(originNode, Map.class);

			if (!hostedImages.isEmpty()) {
				originProduct.put("representativeImage", hostedImages.get(0));
				if (hostedImages.size() > 1) {
					originProduct.put("optionalImages", hostedImages.subList(1, hostedImages.size()));
				}
			}
			if (newDetailHtml != null) {
				originProduct.put("detailContent", newDetailHtml.replace("\"", "\\\"").replace("\n", ""));
			}

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);
			restClient.put("/v2/products/origin-products/" + marketItemId, requestBody);

			log.info("[Smartstore] 이미지/HTML 동기화 완료: {}", marketItemId);
			if (currentRawData != null) {
				if (!hostedImages.isEmpty())
					currentRawData.put("representativeImage", hostedImages.get(0));
				if (newDetailHtml != null)
					currentRawData.put("detailContent", newDetailHtml);
			}
		} catch (RuntimeException e) {
			log.error("[Smartstore] 이미지/HTML 동기화 실패: {}", e.getMessage());
			throw e; // 실패 표면화(SP-A/SP-C 원칙)
		} catch (Exception e) {
			log.error("[Smartstore] 이미지/HTML 동기화 실패: {}", e.getMessage());
			throw new RuntimeException("[Smartstore] 이미지/HTML 동기화 실패: " + e.getMessage(), e); // 실패 표면화(SP-A/SP-C 원칙)
		}
		return currentRawData;
	}
}
