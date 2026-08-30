package com.sbshop.agent.infrastructure.client.sourcing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.port.VendorAwareStockCrawler;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.pricing.VendorShippingCalculator;
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
	private final VendorPricePolicyService vendorPricePolicyService;

	public ScraplingSourcingClient(ObjectMapper objectMapper, String baseUrl, VendorType vendor,
		VendorPricePolicyService vendorPricePolicyService) {
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.vendor = vendor;
		this.vendorPricePolicyService = vendorPricePolicyService;
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
				BigDecimal shipping = resolveShipping(res);
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

	private BigDecimal resolveShipping(JsonNode res) {
		BigDecimal fromScraper = res.hasNonNull("shippingKrw")
			? BigDecimal.valueOf(res.get("shippingKrw").asLong()) : BigDecimal.ZERO;
		if (vendorPricePolicyService == null) {
			return fromScraper;
		}
		VendorPricePolicy policy = vendorPricePolicyService.find(vendor).orElse(null);
		Double weight = res.hasNonNull("weightGrams") ? res.get("weightGrams").asDouble() : null;
		BigDecimal inCurrency = VendorShippingCalculator.amount(weight, policy);
		if (inCurrency == null) {
			log.debug("{} 소싱처 배송비 정책 없음 → 스크래퍼 값 사용", vendor);
			return fromScraper;
		}
		if (inCurrency.signum() == 0) {
			return BigDecimal.ZERO;
		}
		double fx = res.hasNonNull("fxRate") ? res.get("fxRate").asDouble()
			: res.hasNonNull("fxGbpKrw") ? res.get("fxGbpKrw").asDouble() : 0d;
		if (fx <= 0) {
			log.warn("{} 환율을 못 읽어 스크래퍼 배송비를 사용한다", vendor);
			return fromScraper;
		}
		return inCurrency.multiply(BigDecimal.valueOf(fx))
			.setScale(0, java.math.RoundingMode.HALF_UP);
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
