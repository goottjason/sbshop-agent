package com.sbshop.agent.infrastructure.client.cafe24.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24MarketClient implements MarketClient {

	private final ObjectMapper objectMapper;
	private final Cafe24RestClient cafe24RestClient;
	private final HtmlImageExtractor imageExtractor;

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.CAFE24;
	}

	@Override
	public Map<String, String> publish(Product product) {
		log.info("[카페24] 상품 등록 시작: {}", product.getSbCode());
		Map<String, String> identifiers = new HashMap<>();
		identifiers.put("product_no", "C24-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
		return identifiers;
	}

	@Override
	public MarketItemInfo extractMarketItem(String marketItemId) {
		String path = "/admin/products/" + marketItemId + "?embed=variants";
		String responseJson = cafe24RestClient.get(path);
		try {
			JsonNode productNode = objectMapper.readTree(responseJson).path("product");
			String detailHtml = productNode.path("description").asText("");
			String sku = productNode.path("custom_product_code").asText("");
			return MarketItemInfo.builder()
					.isMasterData(true)
					.mappingKey(productNode.path("product_code").asText(""))
					.name(productNode.path("product_name").asText(null))
					.detailHtml(detailHtml)
					.images(imageExtractor.extractSkuImages(detailHtml, sku))
					.rawData(objectMapper.convertValue(productNode, Map.class))
					.build();
		} catch (Exception e) {
			log.error("카페24 상품 정보 추출 실패 (ID: {}): {}", marketItemId, e.getMessage());
			throw new RuntimeException("카페24 데이터 추출 오류", e);
		}
	}

	@Override
	public MarketItemInfo parseLocalData(Map<String, Object> rawData) {
		if (rawData == null || rawData.isEmpty()) {
			return MarketItemInfo.builder().build();
		}
		return MarketItemInfo.builder()
				.isMasterData(true)
				.name(rawData.get("product_name") != null ? String.valueOf(rawData.get("product_name")) : null)
				.mappingKey(rawData.get("custom_product_code") != null ? String.valueOf(rawData.get("custom_product_code")) : "")
				.rawData(rawData)
				.build();
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
			Integer price, Integer stock) {
		try {
			if (currentRawData != null) {
				if (price != null) {
					currentRawData.put("price", price + ".00");
				}
				if (stock != null && currentRawData.containsKey("variants")) {
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> variants = (List<Map<String, Object>>) currentRawData.get("variants");
					if (variants != null && !variants.isEmpty()) {
						variants.get(0).put("quantity", stock);
					}
				}
			}
		} catch (Exception e) {
			log.warn("카페24 로컬 Map 데이터 패치 중 오류", e);
		}
		return currentRawData;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
			List<String> hostedImages, String newDetailHtml) {
		Map<String, Object> descriptionRequestBody = new HashMap<>();
		Map<String, Object> descriptionData = new HashMap<>();
		descriptionData.put("shop_no", 1);
		descriptionData.put("description", newDetailHtml);
		if (hostedImages != null && !hostedImages.isEmpty()) {
			descriptionData.put("use_external_image", "T");
		}
		descriptionRequestBody.put("request", descriptionData);

		try {
			cafe24RestClient.put("/admin/products/" + marketItemId, descriptionRequestBody);
			log.info("[카페24] 상세설명 업데이트 완료: {}", marketItemId);
		} catch (Exception e) {
			log.error("[카페24] 상세설명 업데이트 실패 (ID: {}): {}", marketItemId, e.getMessage());
		}

		if (hostedImages != null && !hostedImages.isEmpty()) {
			try {
				try {
					cafe24RestClient.delete("/admin/products/" + marketItemId + "/images");
				} catch (Exception e) {
					log.warn("[카페24] 기존 이미지 삭제 중 경고: {}", e.getMessage());
				}

				String mainImageUrl = hostedImages.get(0);
				byte[] imageBytes = cafe24RestClient.getExternalImageBytes(mainImageUrl);
				if (imageBytes != null) {
					String base64Content = Base64.getEncoder().encodeToString(imageBytes);
					String dataUri = "data:image/jpeg;base64," + base64Content;

					Map<String, Object> imageRequestBody = new HashMap<>();
					Map<String, Object> imageData = new HashMap<>();
					imageData.put("shop_no", 1);
					imageData.put("image_upload_type", "B");
					imageData.put("detail_image", dataUri);
					imageData.put("list_image", dataUri);
					imageData.put("tiny_image", dataUri);
					imageData.put("small_image", dataUri);
					imageRequestBody.put("request", imageData);

					cafe24RestClient.post("/admin/products/" + marketItemId + "/images", imageRequestBody);
					log.info("[카페24] 이미지 업로드 완료: {}", marketItemId);
				}
			} catch (Exception e) {
				log.error("[카페24] 이미지 업데이트 실패 (ID: {}): {}", marketItemId, e.getMessage());
			}
		}

		if (currentRawData != null) {
			if (hostedImages != null && !hostedImages.isEmpty()) {
				currentRawData.put("detail_image", hostedImages.get(0));
			}
			currentRawData.put("description", newDetailHtml);
		}
		return currentRawData;
	}
}
