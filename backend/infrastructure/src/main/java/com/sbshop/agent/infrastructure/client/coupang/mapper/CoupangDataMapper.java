package com.sbshop.agent.infrastructure.client.coupang.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

	public int getStock(JsonNode firstItem) {
		return firstItem.path("maximumBuyCount").asInt(0);
	}
}
