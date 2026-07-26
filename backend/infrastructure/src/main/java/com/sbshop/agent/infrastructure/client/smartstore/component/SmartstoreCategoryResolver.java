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

/**
 * 스마트스토어 리프 카테고리 해석.
 *
 * <p>커머스API {@code GET /v1/categories?categoryName=…}로 카테고리를 검색해 <b>리프</b>만 고른다.
 * {@code leafCategoryId}는 상품 등록 필수필드이고, 리프가 아닌 카테고리를 넣으면 등록이 거절된다.
 *
 * <p>검색어는 LLM 카테고리 힌트("건강기능식품 &gt; 비타민/미네랄")의 <b>마지막 마디</b>를 우선 쓴다 —
 * 대분류로 검색하면 리프가 아닌 결과만 잔뜩 나온다. 실패하면 설정된 기본 카테고리로 폴백하되
 * {@code confident=false}로 표시해 사람이 확인하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartstoreCategoryResolver implements MarketCategoryResolverPort {

	private static final int MAX_QUERY_TERMS = 3;

	private final SmartstoreRestClient restClient;
	private final ObjectMapper objectMapper;

	/** 검색이 실패했을 때 쓸 리프 카테고리 ID. 미설정이면 미해결로 둔다. */
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

	/** 검색 후보어 — 힌트의 마지막 마디 → 힌트 전체 마디 → 상품명 첫 토큰. */
	private List<String> queryTerms(String categoryHint, String productName) {
		List<String> terms = new ArrayList<>();
		if (categoryHint != null && !categoryHint.isBlank()) {
			String[] parts = categoryHint.split(">");
			for (int i = parts.length - 1; i >= 0; i--) {
				String t = parts[i].trim();
				// "비타민/미네랄"처럼 슬래시가 있으면 앞부분이 더 잘 걸린다.
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
				// last=true 필터가 무시되는 경우가 있어 응답에서도 리프 여부를 다시 확인한다.
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
