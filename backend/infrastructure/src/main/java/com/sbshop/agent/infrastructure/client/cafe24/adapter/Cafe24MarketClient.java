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
		try {
			Map<String, Object> requestBody = new HashMap<>();
			Map<String, Object> productData = new HashMap<>();
			productData.put("shop_no", 1);
			productData.put("product_name", product.getProductName());
			productData.put("custom_product_code", product.getSbCode());
			productData.put("price", product.getSalePrice() != null ? product.getSalePrice().toString() : "0");
			productData.put("supply_quantity", product.getStock() != null ? String.valueOf(product.getStock()) : "0");
			if (product.getBrand() != null)
				productData.put("brand", product.getBrand());
			if (product.getDetailHtml() != null)
				productData.put("description", product.getDetailHtml());

			List<String> hostedImages = product.getHostedImages();
			if (!hostedImages.isEmpty()) {
				productData.put("use_external_image", "T");
				productData.put("list_image", hostedImages.get(0));
				productData.put("detail_image", hostedImages.get(0));
			}

			requestBody.put("request", productData);

			String responseJson = cafe24RestClient.post("/admin/products", requestBody);
			JsonNode responseNode = objectMapper.readTree(responseJson);
			JsonNode productNode = responseNode.path("product");
			String productNo = productNode.path("product_no").asText("");
			String productCode = productNode.path("product_code").asText("");

			log.info("[카페24] 상품 등록 성공: product_no={}, product_code={}", productNo, productCode);
			Map<String, String> identifiers = new HashMap<>();
			identifiers.put("product_no", productNo);
			identifiers.put("product_code", productCode);
			return identifiers;
		} catch (Exception e) {
			log.error("[카페24] 상품 등록 실패: {}", e.getMessage());
			throw new RuntimeException("카페24 상품 등록 오류", e);
		}
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
			.mappingKey(
				rawData.get("custom_product_code") != null ? String.valueOf(rawData.get("custom_product_code")) : "")
			.rawData(rawData)
			.build();
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, Integer stock) {
		// publish()의 price/supply_quantity 필드 + syncImagesAndHtml()의 PUT /admin/products/{id} 패턴을 그대로 사용.
		Map<String, Object> productData = new HashMap<>();
		productData.put("shop_no", 1);
		if (price != null) {
			productData.put("price", price + ".00");
		}
		if (stock != null) {
			productData.put("supply_quantity", String.valueOf(stock));
		}
		Map<String, Object> requestBody = new HashMap<>();
		requestBody.put("request", productData);
		// 실패 시 예외 전파 → 상위에서 '실패 마켓'으로 표면화.
		cafe24RestClient.put("/admin/products/" + marketItemId, requestBody);
		log.info("[카페24] 가격/재고 동기화 완료: {}, price={}, stock={}", marketItemId, price, stock);

		if (currentRawData != null) {
			if (price != null) {
				currentRawData.put("price", price + ".00");
			}
			if (stock != null && currentRawData.containsKey("variants")) {
				@SuppressWarnings("unchecked") List<Map<String, Object>> variants = (List<Map<String, Object>>)currentRawData
					.get("variants");
				if (variants != null && !variants.isEmpty()) {
					variants.get(0).put("quantity", stock);
				}
			}
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
