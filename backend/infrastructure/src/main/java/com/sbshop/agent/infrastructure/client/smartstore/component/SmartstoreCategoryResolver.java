package com.sbshop.agent.infrastructure.client.smartstore.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.application.sourcing.port.MarketCategoryResolverPort;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartstoreCategoryResolver implements MarketCategoryResolverPort {

	private static final int MAX_QUERY_TERMS = 3;

	private final SmartstoreRestClient restClient;
	private final ObjectMapper objectMapper;

	@Value("${market.smartstore.default-leaf-category-id:}")
	private String defaultLeafCategoryId;

	@Override
	public MarketType market() {
		return MarketType.SMART_STORE;
	}

	@Override
	public MarketCategory resolve(String categoryHint, String productName, String brand) {
		for (String term : queryTerms(categoryHint, productName)) {
			MarketCategory found = search(term);
			if (found.isResolved())
				return found;
		}
		if (defaultLeafCategoryId != null && !defaultLeafCategoryId.isBlank()) {
			return new MarketCategory(defaultLeafCategoryId, "기본 카테고리(자동 폴백)", false);
		}
		return MarketCategory.unresolved();
	}

	private List<String> queryTerms(String categoryHint, String productName) {
		List<String> terms = new ArrayList<>();
		if (categoryHint != null && !categoryHint.isBlank()) {
			String[] parts = categoryHint.split(">");
			for (int i = parts.length - 1; i >= 0; i--) {
				String t = parts[i].trim();
				if (t.contains("/"))
					t = t.substring(0, t.indexOf('/')).trim();
				if (t.length() >= 2 && !terms.contains(t))
					terms.add(t);
			}
		}
		if (productName != null && !productName.isBlank()) {
			String first = productName.trim().split("\\s+")[0];
			if (first.length() >= 2 && !terms.contains(first))
				terms.add(first);
		}
		return terms.size() <= MAX_QUERY_TERMS ? terms : terms.subList(0, MAX_QUERY_TERMS);
	}

	private MarketCategory search(String term) {
		try {
			String path = "/v1/categories?categoryName="
				+ URLEncoder.encode(term, StandardCharsets.UTF_8) + "&last=true";
			JsonNode root = objectMapper.readTree(restClient.get(path));
			JsonNode list = root.isArray() ? root : root.path("contents");

			for (JsonNode node : list) {
				if (node.has("last") && !node.path("last").asBoolean(false))
					continue;
				String id = node.path("id").asText(node.path("categoryId").asText(""));
				if (id.isBlank())
					continue;
				String name = node.path("wholeCategoryName").asText(node.path("name").asText(term));
				boolean confident = name.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
				return new MarketCategory(id, name, confident);
			}
		} catch (Exception e) {
			log.warn("[스토어카테고리] 검색 실패 term={}: {}", term, e.getMessage());
		}
		return MarketCategory.unresolved();
	}
}
