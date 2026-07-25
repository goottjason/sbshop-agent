package com.sbshop.agent.infrastructure.client.sourcing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.VendorAwareStockCrawler;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fortnum &amp; Mason(FTN) 재고/가격 크롤러 — Python Scrapling 서비스(sbshop-scraper)를 HTTP로 호출한다.
 * F&amp;M은 JS 렌더링 + Cloudflare 페이지라 JVM HttpClient로는 못 가져오고, 스크래퍼가 브라우저로 렌더해
 * 가격(£)·재고·원가(원, 배대지 배송비+환율 반영)를 계산해 준다.
 *
 * <p>결과 분기(스크래퍼 status):
 * <ul>
 *   <li>ok        → 정상 재고/원가 반영</li>
 *   <li>not_found → 링크 소멸(404): 품절 처리(가격 미변경). {@code sourceGone=true}</li>
 *   <li>blocked/error → 예외를 던져 배치가 실패로 기록(재고/가격 미변경). Cloudflare 차단 오품절 방지</li>
 * </ul>
 */
@Slf4j
@Component
public class ScraplingSourcingClient implements VendorAwareStockCrawler {

	// HTTP/1.1 고정: 기본 HTTP/2 협상 시 uvicorn(HTTP/1.1 전용)에 POST 본문이 유실돼 422가 난다.
	private final HttpClient httpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(15))
		.build();
	private final ObjectMapper objectMapper;
	private final String baseUrl;

	public ScraplingSourcingClient(ObjectMapper objectMapper,
		@Value("${scraper.base-url:http://localhost:8099}") String baseUrl) {
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
	}

	@Override
	public VendorType vendor() {
		return VendorType.FTN;
	}

	@Override
	public StockStatus checkStockStatus(String sourceUrl) {
		return checkStockWithDetails(sourceUrl).status();
	}

	@Override
	public StockCheckResult checkStockWithDetails(String sourceUrl) {
		JsonNode res = call(sourceUrl);
		String status = res.path("status").asText("error");
		switch (status) {
			case "ok": {
				if (!res.hasNonNull("costKrw")) {
					throw new IllegalStateException("F&M 원가(costKrw) 없음 — 스킵: " + sourceUrl);
				}
				// 재고 판별 불가(inStock 누락/null)면 오품절 방지 위해 스킵(예외→배치 실패 기록).
				if (!res.hasNonNull("inStock")) {
					throw new IllegalStateException("F&M 재고 판별 불가(inStock 없음) — 스킵: " + sourceUrl);
				}
				boolean inStock = res.path("inStock").asBoolean(false);
				BigDecimal costPrice = BigDecimal.valueOf(res.get("costKrw").asLong());
				return new StockCheckResult(
					inStock ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK,
					costPrice, inStock ? 100 : 0, null, false);
			}
			case "not_found":
				// 링크 소멸(404) → 품절(가격 미변경). sourceGone=true 신호로 배치가 재고만 내린다.
				log.info("F&M 링크 소멸(404) → 품절 처리: {}", sourceUrl);
				return new StockCheckResult(StockStatus.OUT_OF_STOCK, null, 0, null, true);
			case "blocked":
				throw new IllegalStateException("Cloudflare/봇차단 의심(스킵·추적) http="
					+ res.path("httpStatus").asText("?") + " url=" + sourceUrl);
			default:
				throw new IllegalStateException("F&M 스크랩 실패(" + status + "): "
					+ res.path("error").asText("") + " url=" + sourceUrl);
		}
	}

	private JsonNode call(String url) {
		try {
			String body = objectMapper.writeValueAsString(Map.of("url", url, "vendor", "FTN"));
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/scrape/stock-price"))
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(90))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				throw new IllegalStateException("스크래퍼 HTTP " + resp.statusCode() + ": " + resp.body());
			}
			return objectMapper.readTree(resp.body());
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("스크래퍼 호출 실패: " + e.getMessage(), e);
		}
	}
}
