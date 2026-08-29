package com.sbshop.agent.infrastructure.client.sourcing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.VendorAwareStockCrawler;
import com.sbshop.agent.core.domain.product.enums.SourceGoneReason;
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

@Slf4j
public class ScraplingSourcingClient implements VendorAwareStockCrawler {

	private final HttpClient httpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(15))
		.build();
	private final ObjectMapper objectMapper;
	private final String baseUrl;
	private final VendorType vendor;

	public ScraplingSourcingClient(ObjectMapper objectMapper, String baseUrl, VendorType vendor) {
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.vendor = vendor;
	}

	@Override
	public VendorType vendor() {
		return vendor;
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
				if (!res.hasNonNull("goodsKrw")) {
					throw new IllegalStateException("F&M 원가(goodsKrw) 없음 — 스킵: " + sourceUrl);
				}
				if (!res.hasNonNull("inStock")) {
					throw new IllegalStateException("F&M 재고 판별 불가(inStock 없음) — 스킵: " + sourceUrl);
				}
				boolean inStock = res.path("inStock").asBoolean(false);
				BigDecimal goods = BigDecimal.valueOf(res.get("goodsKrw").asLong());
				BigDecimal shipping = res.hasNonNull("shippingKrw")
					? BigDecimal.valueOf(res.get("shippingKrw").asLong()) : BigDecimal.ZERO;
				return new StockCheckResult(
					inStock ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK,
					goods, inStock ? 100 : 0, null, false, shipping);
			}
			case "not_found":
				log.info("{} 링크 소멸(404) → 폐기 후보로 기록: {}", vendor, sourceUrl);
				return new StockCheckResult(StockStatus.OUT_OF_STOCK, null, 0, null, true, null,
					SourceGoneReason.LINK_DEAD);
			case "discontinued":
				log.info("{} 단종 표기 → 폐기 후보로 기록: {}", vendor, sourceUrl);
				return new StockCheckResult(StockStatus.OUT_OF_STOCK, null, 0, null, true, null,
					SourceGoneReason.DISCONTINUED);
			case "blocked":
				throw new IllegalStateException("Cloudflare/봇차단 의심(스킵·추적) http="
					+ res.path("httpStatus").asText("?") + " url=" + sourceUrl);
			default:
				throw new IllegalStateException(vendor + " 스크랩 실패(" + status + "): "
					+ res.path("error").asText("") + " url=" + sourceUrl);
		}
	}

	private JsonNode call(String url) {
		try {
			String body = objectMapper.writeValueAsString(Map.of("url", url, "vendor", vendor.name()));
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
