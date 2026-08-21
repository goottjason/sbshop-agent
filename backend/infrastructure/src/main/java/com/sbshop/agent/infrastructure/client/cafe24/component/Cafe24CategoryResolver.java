package com.sbshop.agent.infrastructure.client.cafe24.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.application.sourcing.port.MarketCategoryResolverPort;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24CategoryResolver implements MarketCategoryResolverPort {

	private static final String LIST_PATH = "/admin/categories?limit=100";

	private final Cafe24RestClient restClient;
	private final ObjectMapper objectMapper;

	@Value("${market.cafe24.default-category-no:}")
	private String configuredCategoryNo;

	private final AtomicReference<List<Category>> cache = new AtomicReference<>();

	@Override
	public MarketType market() {
		return MarketType.CAFE24;
	}

	@Override
	public MarketCategory resolve(String categoryHint, String productName, String brand) {
		if (configuredCategoryNo != null && !configuredCategoryNo.isBlank()) {
			return new MarketCategory(configuredCategoryNo.trim(), "설정 지정 분류", true);
		}

		List<Category> categories = categories();
		if (categories.isEmpty())
			return MarketCategory.unresolved();

		for (String term : hintTerms(categoryHint)) {
			for (Category c : categories) {
				if (c.name != null && c.name.toLowerCase(Locale.ROOT)
					.contains(term.toLowerCase(Locale.ROOT))) {
					return new MarketCategory(c.no, c.name, true);
				}
			}
		}

		Category fallback = categories.get(0);
		return new MarketCategory(fallback.no, fallback.name + " (자동 폴백)", false);
	}

	private List<String> hintTerms(String categoryHint) {
		List<String> terms = new ArrayList<>();
		if (categoryHint == null || categoryHint.isBlank())
			return terms;
		for (String part : categoryHint.split(">")) {
			String t = part.trim();
			if (t.contains("/"))
				t = t.substring(0, t.indexOf('/')).trim();
			if (t.length() >= 2)
				terms.add(t);
		}
		Collections.reverse(terms);
		return terms;
	}

	private List<Category> categories() {
		List<Category> cached = cache.get();
		if (cached != null)
			return cached;
		try {
			JsonNode root = objectMapper.readTree(restClient.get(LIST_PATH));
			List<Category> out = new ArrayList<>();
			for (JsonNode n : root.path("categories")) {
				String no = n.path("category_no").asText("");
				String name = n.path("category_name").asText("");
				if (!no.isBlank())
					out.add(new Category(no, name));
			}
			out.sort((a, b) -> Integer.compare(parseInt(a.no), parseInt(b.no)));
			if (!out.isEmpty()) {
				cache.set(List.copyOf(out));
				log.info("[카페24분류] {}개 분류를 조회해 캐시했습니다(폴백={})", out.size(), out.get(0).no);
			}
			return out;
		} catch (Exception e) {
			log.warn("[카페24분류] 분류 목록 조회 실패: {}", e.getMessage());
			return List.of();
		}
	}

	private int parseInt(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

	private record Category(String no, String name) {
	}
}
