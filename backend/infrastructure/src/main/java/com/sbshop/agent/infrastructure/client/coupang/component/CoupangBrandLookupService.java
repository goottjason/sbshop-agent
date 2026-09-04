package com.sbshop.agent.infrastructure.client.coupang.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.port.BrandLookupOutcome;
import com.sbshop.agent.core.application.product.port.CoupangBrandLookupPort;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoupangBrandLookupService implements CoupangBrandLookupPort {

	private static final String BRAND_SEARCH_PATH = "/v2/providers/seller_api/apis/api/v1/marketplace/brands/search";

	private final CoupangRestClient restClient;
	private final ObjectMapper objectMapper;
	private static final String ENROLLED_PATH =
		"/v2/providers/seller_api/apis/api/v1/marketplace/brands/enrolled";

	private final Map<String, BrandLookupOutcome> cache = new ConcurrentHashMap<>();

	private volatile List<String> enrolledCache;

	@Override
	public BrandLookupOutcome findOfficialBrandName(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return BrandLookupOutcome.notRegistered();
		}
		BrandLookupOutcome cached = cache.get(keyword);
		if (cached != null) {
			return cached;
		}
		BrandLookupOutcome outcome = search(keyword);
		if (outcome.isCacheable()) {
			cache.put(keyword, outcome);
		}
		return outcome;
	}

	@Override
	public List<String> enrolledBrandNames() {
		List<String> cached = enrolledCache;
		if (cached != null) {
			return cached;
		}
		try {
			String response = restClient.requestWithBody("GET", ENROLLED_PATH, null);
			JsonNode root = objectMapper.readTree(response);
			if (!"SUCCESS".equals(root.path("code").asText())) {
				log.warn("[쿠팡 등록 브랜드] code != SUCCESS — 캐시하지 않는다: {}", response);
				return List.of();
			}
			List<String> names = new ArrayList<>();
			for (JsonNode item : root.path("data")) {
				String brandName = item.path("brandName").asText();
				if (!brandName.isBlank()) {
					names.add(brandName);
				}
			}
			enrolledCache = List.copyOf(names);
			log.info("[쿠팡 등록 브랜드] {}건 확보", names.size());
			return enrolledCache;
		} catch (Exception e) {
			log.error("[쿠팡 등록 브랜드] 조회 실패 — 캐시하지 않는다", e);
			return List.of();
		}
	}

	private BrandLookupOutcome search(String keyword) {
		try {
			String response = restClient.requestWithBody("POST", BRAND_SEARCH_PATH,
				Map.of("brandName", keyword, "countPerPage", 10, "page", 1));
			JsonNode root = objectMapper.readTree(response);
			if (!"SUCCESS".equals(root.path("code").asText())) {
				log.warn("[쿠팡 브랜드 검색] code != SUCCESS — 없음이 아니라 모름이다: keyword={}, response={}",
					keyword, response);
				return BrandLookupOutcome.lookupFailed();
			}
			return pickTopCandidate(root.path("data").path("items"), keyword);
		} catch (Exception e) {
			log.error("[쿠팡 브랜드 검색] 조회 실패 — 캐시하지 않는다: keyword={}", keyword, e);
			return BrandLookupOutcome.lookupFailed();
		}
	}

	private BrandLookupOutcome pickTopCandidate(JsonNode items, String keyword) {
		List<String> candidates = new ArrayList<>();
		for (JsonNode item : items) {
			String brandName = item.path("brandName").asText();
			if (!brandName.isBlank()) {
				candidates.add(brandName);
			}
		}
		if (candidates.isEmpty()) {
			log.info("[쿠팡 브랜드 검색] 후보 없음: keyword={}", keyword);
			return BrandLookupOutcome.notRegistered();
		}
		return BrandLookupOutcome.matched(candidates.get(0), candidates);
	}

}
