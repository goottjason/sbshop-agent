package com.sbshop.agent.infrastructure.client.sourcing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.DiscoveredCandidateDto;
import com.sbshop.agent.core.application.sourcing.dto.DiscoveryCrawlResult;
import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;
import com.sbshop.agent.core.application.sourcing.port.BestsellerCrawlerPort;
import com.sbshop.agent.core.application.sourcing.port.ProductDetailCrawlerPort;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * iHerb 발굴·상세 크롤 어댑터 — Python Scrapling 사이드카(sbshop-scraper)를 HTTP로 호출한다.
 *
 * <p>기존 {@code IherbScraperClient}가 쓰던 {@code catalog.app.iherb.com} JSON API는
 * Cloudflare 챌린지로 403을 반환한다(2026-07 실측). 사이드카의 브라우저 페처는 통과한다.
 *
 * <p>타임아웃이 크다: 베스트셀러 발굴은 (카테고리 수 × 페이지 수)만큼 브라우저 렌더를 돌리므로
 * 4카테고리 × 3페이지면 12회 렌더 = 수 분이 걸린다. 스케줄러/비동기 경로에서만 호출한다.
 */
@Slf4j
@Component
public class ScraplingIherbClient implements BestsellerCrawlerPort, ProductDetailCrawlerPort {

	/** 페이지당 브라우저 렌더 예상 시간(초) — 전체 타임아웃 산정에 쓴다. */
	private static final int SECONDS_PER_PAGE = 45;
	private static final Duration DETAIL_TIMEOUT = Duration.ofSeconds(120);
	private static final Duration MIN_DISCOVER_TIMEOUT = Duration.ofMinutes(3);
	private static final Duration MAX_DISCOVER_TIMEOUT = Duration.ofMinutes(30);

	// HTTP/1.1 고정: 기본 HTTP/2 협상 시 uvicorn(HTTP/1.1 전용)에 POST 본문이 유실돼 422가 난다.
	private final HttpClient httpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(15))
		.build();
	private final ObjectMapper objectMapper;
	private final String baseUrl;

	public ScraplingIherbClient(ObjectMapper objectMapper,
		@Value("${scraper.base-url:http://localhost:8099}") String baseUrl) {
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
	}

	// ── 발굴 ──────────────────────────────────────────────────────────────────

	@Override
	public DiscoveryCrawlResult discover(List<String> categorySlugs, int pagesPerCategory) {
		if (categorySlugs == null || categorySlugs.isEmpty()) {
			return DiscoveryCrawlResult.empty("크롤 대상 카테고리가 비어 있습니다.");
		}
		Duration timeout = discoverTimeout(categorySlugs.size(), pagesPerCategory);
		Map<String, Object> body = Map.of(
			"categories", categorySlugs,
			"pages", pagesPerCategory,
			"vendor", "IHB");

		JsonNode res;
		try {
			res = post("/discover/bestsellers", body, timeout);
		} catch (Exception e) {
			// 사이드카 자체가 죽었을 때 — 후보 0건을 "인기 상품 없음"으로 오인하지 않도록 사유를 올린다.
			log.error("[소싱발굴] 스크래퍼 호출 실패: {}", e.getMessage());
			return DiscoveryCrawlResult.empty("스크래퍼 호출 실패: " + e.getMessage());
		}

		List<DiscoveredCandidateDto> candidates = new ArrayList<>();
		for (JsonNode c : res.path("cards")) {
			candidates.add(toCandidate(c));
		}
		List<String> failures = new ArrayList<>();
		for (JsonNode f : res.path("failures")) {
			failures.add("%s p%d: %s".formatted(
				f.path("categorySlug").asText("?"), f.path("page").asInt(0), f.path("reason").asText("")));
		}
		log.info("[소싱발굴] 후보 {}건 수집, 실패 {}건", candidates.size(), failures.size());
		return new DiscoveryCrawlResult(candidates, failures);
	}

	/** 렌더 횟수에 비례한 타임아웃. 너무 짧으면 정상 크롤이 중단되고, 무제한이면 스레드를 붙잡는다. */
	private Duration discoverTimeout(int categoryCount, int pages) {
		Duration est = Duration.ofSeconds((long)categoryCount * Math.max(pages, 1) * SECONDS_PER_PAGE);
		if (est.compareTo(MIN_DISCOVER_TIMEOUT) < 0)
			return MIN_DISCOVER_TIMEOUT;
		return est.compareTo(MAX_DISCOVER_TIMEOUT) > 0 ? MAX_DISCOVER_TIMEOUT : est;
	}

	private DiscoveredCandidateDto toCandidate(JsonNode c) {
		return new DiscoveredCandidateDto(
			c.path("vendor").asText("IHB"),
			c.path("externalId").asText(null),
			c.path("sourceUrl").asText(null),
			text(c, "partNumber"),
			text(c, "brand"),
			text(c, "brandCode"),
			text(c, "nameKo"),
			text(c, "categorySlug"),
			text(c, "imageUrl"),
			decimal(c, "listPrice"),
			decimal(c, "discountPrice"),
			integer(c, "discountPct"),
			decimal(c, "rating"),
			integer(c, "reviewCount"),
			integer(c, "sales30d"),
			integer(c, "rankPosition"),
			c.path("isSponsored").asBoolean(false),
			c.path("isOutOfStock").asBoolean(false),
			c.path("isDiscontinued").asBoolean(false));
	}

	// ── 상세 ──────────────────────────────────────────────────────────────────

	@Override
	public ProductDetailDto fetchDetail(String sourceUrl) {
		JsonNode res;
		try {
			res = post("/scrape/product-detail", Map.of("url", sourceUrl, "vendor", "IHB"), DETAIL_TIMEOUT);
		} catch (Exception e) {
			log.warn("[소싱상세] 크롤 실패 url={}: {}", sourceUrl, e.getMessage());
			return failedDetail(sourceUrl, "스크래퍼 호출 실패: " + e.getMessage());
		}

		List<String> images = new ArrayList<>();
		for (JsonNode i : res.path("images")) {
			images.add(i.asText());
		}
		return new ProductDetailDto(
			res.path("ok").asBoolean(false),
			res.path("status").asText("error"),
			sourceUrl,
			text(res, "externalId"),
			text(res, "nameKo"),
			text(res, "brandKo"),
			text(res, "brandCode"),
			text(res, "rootCategory"),
			res.path("isDiscontinued").asBoolean(false),
			text(res, "partNumber"),
			text(res, "upc"),
			decimal(res, "priceKrw"),
			decimal(res, "listPriceKrw"),
			res.hasNonNull("inStock") ? res.get("inStock").asBoolean() : null,
			decimal(res, "shippingWeightGrams"),
			integer(res, "packageQuantity"),
			text(res, "dimensions"),
			text(res, "ingredientsRaw"),
			text(res, "mainIngredients"),
			text(res, "otherIngredients"),
			text(res, "description"),
			text(res, "usage"),
			text(res, "caution"),
			images,
			text(res, "error"));
	}

	private ProductDetailDto failedDetail(String url, String error) {
		return new ProductDetailDto(false, "error", url, null, null, null, null, null, false,
			null, null, null, null, null, null, null, null, null, null, null, null, null, null,
			List.of(), error);
	}

	// ── HTTP ──────────────────────────────────────────────────────────────────

	private JsonNode post(String path, Object body, Duration timeout) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + path))
			.timeout(timeout)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException(
				"스크래퍼 HTTP %d: %s".formatted(response.statusCode(), truncate(response.body())));
		}
		return objectMapper.readTree(response.body());
	}

	private static String truncate(String s) {
		if (s == null)
			return "";
		return s.length() <= 300 ? s : s.substring(0, 300) + "...";
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asText();
	}

	private static BigDecimal decimal(JsonNode n, String field) {
		JsonNode v = n.path(field);
		return v.isMissingNode() || v.isNull() ? null : BigDecimal.valueOf(v.asDouble());
	}

	private static Integer integer(JsonNode n, String field) {
		JsonNode v = n.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asInt();
	}
}
