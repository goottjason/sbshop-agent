package com.sbshop.agent.infrastructure.client.demand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.ShoppingStats;
import com.sbshop.agent.core.application.sourcing.port.ShoppingMarketPort;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 네이버 검색 API(쇼핑) 클라이언트 — 경쟁 상품 수와 국내 최저가.
 *
 * <p>{@code GET https://openapi.naver.com/v1/search/shop.json?query=…&sort=asc}
 * 응답의 {@code total}이 경쟁강도, 최저 {@code lprice}가 국내 최저가다.
 * 개발자센터에서 발급하는 무료 자격증명(일 25,000회)만 있으면 된다.
 *
 * <p>쿠팡·네이버쇼핑 검색 페이지는 스크래핑이 막혀 있어(각각 403/418, StealthyFetcher도 실패)
 * 국내 수요 신호는 이 공식 API가 유일한 경로다.
 *
 * <p>자격증명이 없으면 {@link #isEnabled()}가 false를 반환하고 스코어링이 해당 가중치를 빼고
 * 정규화한다 — 키 없이도 파이프라인 전체는 돈다.
 */
@Slf4j
@Component
public class NaverShoppingSearchClient implements ShoppingMarketPort {

	private static final String ENDPOINT = "https://openapi.naver.com/v1/search/shop.json";
	/** 최저가·중앙값 산정을 위한 표본 수. 100이 API 상한이지만 40이면 충분하고 빠르다. */
	private static final int DISPLAY = 40;

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private final ObjectMapper objectMapper;
	private final String clientId;
	private final String clientSecret;

	public NaverShoppingSearchClient(ObjectMapper objectMapper,
		@Value("${naver.openapi.client-id:}")
		String clientId,
		@Value("${naver.openapi.client-secret:}")
		String clientSecret) {
		this.objectMapper = objectMapper;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
	}

	@Override
	public boolean isEnabled() {
		return clientId != null && !clientId.isBlank()
			&& clientSecret != null && !clientSecret.isBlank();
	}

	@Override
	public Optional<ShoppingStats> lookup(String query) {
		if (!isEnabled() || query == null || query.isBlank())
			return Optional.empty();

		String url = ENDPOINT + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
			+ "&display=" + DISPLAY + "&sort=asc";
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("X-Naver-Client-Id", clientId)
				.header("X-Naver-Client-Secret", clientSecret)
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("[수요신호] 네이버 쇼핑검색 HTTP {} — query={}", response.statusCode(), query);
				return Optional.empty();
			}
			return Optional.of(parse(query, objectMapper.readTree(response.body())));
		} catch (Exception e) {
			// 신호 하나가 없다고 후보를 버리지 않는다 — 로그만 남기고 빈 값으로 진행.
			log.warn("[수요신호] 네이버 쇼핑검색 실패 query={}: {}", query, e.getMessage());
			return Optional.empty();
		}
	}

	private ShoppingStats parse(String query, JsonNode root) {
		int total = root.path("total").asInt(0);
		List<BigDecimal> prices = new ArrayList<>();
		Set<String> categories = new LinkedHashSet<>();

		for (JsonNode item : root.path("items")) {
			long lprice = item.path("lprice").asLong(0);
			// 0원·비정상 저가는 표본에서 뺀다(옵션 미끼상품이 최저가를 왜곡한다).
			if (lprice > 0)
				prices.add(BigDecimal.valueOf(lprice));
			String path = categoryPath(item);
			if (!path.isBlank())
				categories.add(path);
		}

		prices.sort(BigDecimal::compareTo);
		BigDecimal lowest = prices.isEmpty() ? null : prices.get(0);
		BigDecimal median = prices.isEmpty() ? null : prices.get(prices.size() / 2);

		return new ShoppingStats(query, total, lowest, median,
			categories.stream().limit(5).toList());
	}

	private String categoryPath(JsonNode item) {
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i <= 4; i++) {
			String c = item.path("category" + i).asText("");
			if (c.isBlank())
				break;
			if (sb.length() > 0)
				sb.append(" > ");
			sb.append(c);
		}
		return sb.toString();
	}
}
