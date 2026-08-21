package com.sbshop.agent.infrastructure.client.coupang.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.application.sourcing.port.MarketCategoryResolverPort;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangCategoryResolver implements MarketCategoryResolverPort {

	private static final String PREDICT_PATH = "/v2/providers/openapi/apis/api/v1/categorization/predict";

	private static final Set<Long> SAFE_OVERSEAS_CATEGORIES = Set.of(
		73132L, 73133L, 73134L, 73138L, 73141L, 73142L, 73144L, 73145L, 73146L, 73199L,
		73859L, 73861L, 73872L, 73905L, 73946L, 74154L, 74187L);

	private static final Long FALLBACK_HEALTH_CATEGORY_ID = 73199L;

	private final CoupangRestClient restClient;
	private final ObjectMapper objectMapper;

	@Override
	public MarketType market() {
		return MarketType.COUPANG;
	}

	@Override
	public MarketCategory resolve(String categoryHint, String productName, String brand) {
		if (productName == null || productName.isBlank())
			return MarketCategory.unresolved();

		try {
			String response = restClient.requestWithBody("POST", PREDICT_PATH, Map.of(
				"productName", productName,
				"brand", brand != null ? brand : ""));
			JsonNode data = objectMapper.readTree(response).path("data");
			long predicted = data.path("predictedCategoryId").asLong(0);
			String name = data.path("predictedCategoryName").asText(null);

			if (SAFE_OVERSEAS_CATEGORIES.contains(predicted)) {
				return new MarketCategory(String.valueOf(predicted), name, true);
			}
			log.info("[쿠팡카테고리] 안전목록 밖 예측({}) — 폴백 후 검수 요청: {}", predicted, productName);
			return new MarketCategory(String.valueOf(FALLBACK_HEALTH_CATEGORY_ID),
				name != null ? name + " (자동 폴백)" : "건강기능식품 (자동 폴백)", false);
		} catch (Exception e) {
			log.warn("[쿠팡카테고리] 추천 API 실패 — 폴백: {}", e.getMessage());
			return new MarketCategory(String.valueOf(FALLBACK_HEALTH_CATEGORY_ID),
				"건강기능식품 (API 실패 폴백)", false);
		}
	}
}
