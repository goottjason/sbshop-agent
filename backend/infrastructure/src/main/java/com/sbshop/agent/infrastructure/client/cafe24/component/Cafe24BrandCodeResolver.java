package com.sbshop.agent.infrastructure.client.cafe24.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24BrandCodeResolver {

	private final Cafe24RestClient cafe24RestClient;
	private final ObjectMapper objectMapper;

	private final Map<String, String> cache = new ConcurrentHashMap<>();

	public String resolve(String brandName) {
		String name = brandName.trim();
		String cached = cache.get(name);
		if (cached != null) {
			return cached;
		}
		String code = findExisting(name).orElseGet(() -> register(name));
		cache.put(name, code);
		return code;
	}

	private Optional<String> findExisting(String brandName) {
		try {
			JsonNode root = objectMapper.readTree(
				cafe24RestClient.get("/admin/brands?brand_name=" + brandName));
			for (JsonNode brand : root.path("brands")) {
				if (brandName.equals(brand.path("brand_name").asText(""))) {
					return Optional.of(brand.path("brand_code").asText());
				}
			}
			return Optional.empty();
		} catch (Exception e) {
			throw new IllegalStateException("[카페24] 브랜드 조회 실패: " + brandName, e);
		}
	}

	private String register(String brandName) {
		Map<String, Object> request = new HashMap<>();
		request.put("brand_name", brandName);
		request.put("use_brand", "T");
		Map<String, Object> requestBody = new HashMap<>();
		requestBody.put("shop_no", 1);
		requestBody.put("request", request);
		try {
			JsonNode root = objectMapper.readTree(cafe24RestClient.post("/admin/brands", requestBody));
			String code = root.path("brand").path("brand_code").asText("");
			if (code.isBlank()) {
				throw new IllegalStateException("[카페24] 브랜드 등록 응답에 brand_code 없음: " + brandName);
			}
			log.info("[카페24] 브랜드 신규 등록: {} -> {}", brandName, code);
			return code;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("[카페24] 브랜드 등록 실패: " + brandName, e);
		}
	}
}
