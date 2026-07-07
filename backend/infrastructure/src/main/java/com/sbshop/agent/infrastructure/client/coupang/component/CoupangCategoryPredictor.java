package com.sbshop.agent.infrastructure.client.coupang.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangCategoryPredictor {

	private final CoupangRestClient restClient;
	private final ObjectMapper objectMapper;

	private static final Set<Long> SAFE_OVERSEAS_CATEGORIES = Set.of(
		73132L, 73133L, 73134L, 73138L, 73141L, 73142L, 73144L, 73145L, 73146L, 73199L,
		73859L, 73861L, 73872L, 73905L, 73946L, 74154L, 74187L);

	private static final Long FALLBACK_HEALTH_CATEGORY_ID = 73199L;

	public Long predictCategory(Product product) {
		String path = "/v2/providers/openapi/apis/api/v1/categorization/predict";
		Map<String, String> body = Map.of(
			"productName", product.getBaseName() != null ? product.getBaseName() : "",
			"brand", product.getBrand() != null ? product.getBrand() : "");
		try {
			String response = restClient.requestWithBody("POST", path, body);
			Long predictedId = objectMapper.readTree(response).path("data").path("predictedCategoryId").asLong();
			if (SAFE_OVERSEAS_CATEGORIES.contains(predictedId)) {
				log.info("[Category Predict] 안전 카테고리 매칭: {}, ID={}", product.getBaseName(), predictedId);
				return predictedId;
			}
			log.warn("[Category Predict] 위험 카테고리 감지, 폴백: {}, ID={}", product.getBaseName(), predictedId);
			return FALLBACK_HEALTH_CATEGORY_ID;
		} catch (Exception e) {
			log.error("[Category Predict] API 호출 실패, 폴백: {}", e.getMessage());
			return FALLBACK_HEALTH_CATEGORY_ID;
		}
	}
}
