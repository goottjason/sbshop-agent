package com.sbshop.agent.infrastructure.client.coupang.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CoupangDataMapper {

	private final ObjectMapper objectMapper;

	public Map<String, String> buildIdentifiers(String sellerProductId, JsonNode firstItem) {
		Map<String, String> ids = new HashMap<>();
		ids.put("sellerProductId", sellerProductId);
		ids.put("vendorItemId", firstItem.path("vendorItemId").asText(""));
		ids.put("barcode", firstItem.path("barcode").asText(""));
		ids.put("externalVendorSku", firstItem.path("externalVendorSku").asText(""));
		return ids;
	}

	public Map<String, Object> buildRawData(JsonNode dataNode) {
		return objectMapper.convertValue(dataNode, new TypeReference<Map<String, Object>>() {});
	}

	public String extractMergedHtmlDescription(JsonNode firstItem) {
		JsonNode contents = firstItem.path("contents");
		if (!contents.isArray())
			return "";
		StringBuilder htmlBuilder = new StringBuilder();
		for (JsonNode content : contents) {
			String detailType = content.path("detailType").asText("");
			String contentValue = content.path("content").asText("");
			if ("TEXT".equalsIgnoreCase(detailType) || "HTML".equalsIgnoreCase(detailType)) {
				htmlBuilder.append(contentValue).append("<br/>");
			} else if ("IMAGE".equalsIgnoreCase(detailType)) {
				htmlBuilder.append("<img src='").append(contentValue).append("'/><br/>");
			}
		}
		return htmlBuilder.toString();
	}

	public BigDecimal getPrice(JsonNode firstItem) {
		String priceStr = firstItem.path("salePrice").asText("0");
		return new BigDecimal(priceStr.isBlank() ? "0" : priceStr);
	}

	public int getStock(JsonNode firstItem) {
		return firstItem.path("maximumBuyCount").asInt(0);
	}
}
