package com.sbshop.agent.infrastructure.client.smartstore;

import com.fasterxml.jackson.databind.JsonNode;

public final class SmartStoreDispatchResult {

	private SmartStoreDispatchResult() {}

	public static void verifyAccepted(JsonNode response, String productOrderId) {
		if (response == null) {
			return;
		}

		String topLevelCode = response.path("code").asText("");
		if (!topLevelCode.isEmpty()) {
			throw new RuntimeException("스마트스토어 발송 실패(" + topLevelCode + "): "
				+ response.path("message").asText(""));
		}

		JsonNode failures = response.path("data").path("failProductOrderInfos");
		if (failures.isArray() && !failures.isEmpty()) {
			JsonNode first = failures.get(0);
			throw new RuntimeException("스마트스토어 발송 실패("
				+ first.path("code").asText("") + "): " + first.path("message").asText("")
				+ " — 상품주문 " + first.path("productOrderId").asText(productOrderId));
		}
	}
}
