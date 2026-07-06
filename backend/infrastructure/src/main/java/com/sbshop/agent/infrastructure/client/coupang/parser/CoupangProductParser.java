package com.sbshop.agent.infrastructure.client.coupang.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CoupangProductParser {

	private final ObjectMapper objectMapper;

	public JsonNode parseDataNode(String json) throws Exception {
		return objectMapper.readTree(json).path("data");
	}

	public JsonNode getFirstItem(JsonNode dataNode) {
		JsonNode items = dataNode.path("items");
		if (items.isArray() && !items.isEmpty()) {
			return items.get(0);
		}
		return objectMapper.createObjectNode();
	}
}
