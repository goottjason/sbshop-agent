package com.sbshop.agent.infrastructure.client.coupang.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.port.BrandLookupOutcome;
import com.sbshop.agent.core.application.product.port.CoupangBrandLookupPort;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import java.util.Locale;
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
	private final Map<String, BrandLookupOutcome> cache = new ConcurrentHashMap<>();

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
			return findExactMatch(root.path("data").path("items"), keyword);
		} catch (Exception e) {
			log.error("[쿠팡 브랜드 검색] 조회 실패 — 캐시하지 않는다: keyword={}", keyword, e);
			return BrandLookupOutcome.lookupFailed();
		}
	}

	private BrandLookupOutcome findExactMatch(JsonNode items, String keyword) {
		String normalizedKeyword = normalize(keyword);
		List<String> candidates = new ArrayList<>();
		String exact = null;
		for (JsonNode item : items) {
			String brandName = item.path("brandName").asText();
			if (brandName.isBlank()) {
				continue;
			}
			candidates.add(brandName);
			if (exact == null && normalize(brandName).equals(normalizedKeyword)) {
				exact = brandName;
			}
		}
		if (exact != null) {
			return BrandLookupOutcome.matched(exact, candidates);
		}
		log.info("[쿠팡 브랜드 검색] 정확일치 없음: keyword={}, 후보={}", keyword, candidates);
		return BrandLookupOutcome.notRegistered(candidates);
	}

	private static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[\\s'\\-.&]", "");
	}
}
