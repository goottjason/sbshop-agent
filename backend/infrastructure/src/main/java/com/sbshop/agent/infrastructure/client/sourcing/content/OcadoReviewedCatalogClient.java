package com.sbshop.agent.infrastructure.client.sourcing.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.application.product.source.ProductSourceHttpGuard;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.infrastructure.client.smartstore.client.InspectionRetryAfter;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dedicated browser-sidecar contract. Never uses the legacy generic crawler's defaults. */
@Component
public class OcadoReviewedCatalogClient {
	private final ObjectMapper mapper;
	private final URI endpoint;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10)).build();

	public OcadoReviewedCatalogClient(ObjectMapper mapper,
		@Value("${scraper.base-url:http://localhost:8099}")
		String baseUrl) {
		this.mapper = mapper;
		this.endpoint = URI.create(baseUrl.replaceAll("/$", "") + "/scrape/reviewed/ocado");
	}

	public SupplierCatalogClient.Catalog fetch(String sourceUrl, boolean content) {
		ProductContentUrls.source(VendorType.OCD, sourceUrl);
		String mode = content ? "CONTENT" : "PRICE_STOCK";
		try {
			var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(70))
				.header("Content-Type", "application/json").header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers
					.ofString(mapper.writeValueAsString(Map.of("url", sourceUrl, "mode", mode))))
				.build();
			ProductSourceHttpGuard.check();
			var response = http.send(request, info -> new IherbProductContentSource.LimitedBodySubscriber(500_000));
			if (response.statusCode() == 429)
				throw new ProductContentThrottledException(InspectionRetryAfter.parse(
					response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
			if (response.statusCode() != 200) {
				if (response.statusCode() == 422) {
					try {
						var code = ProductContentFailureException.Code
							.valueOf(mapper.readTree(response.body()).path("errorCode").asText());
						throw new ProductContentFailureException(code);
					} catch (IllegalArgumentException ignored) { /* Unknown remote message is never exposed. */ }
				}
				throw ProductContentFailureException.http(response.statusCode());
			}
			return parse(mapper.readTree(response.body()), sourceUrl, content);
		} catch (ProductContentFailureException | ProductContentThrottledException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw fail(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		} catch (Exception e) {
			throw fail(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		}
	}

	SupplierCatalogClient.Catalog parse(JsonNode data, String sourceUrl, boolean content) {
		ProductContentUrls.source(VendorType.OCD, sourceUrl);
		String mode = content ? "CONTENT" : "PRICE_STOCK";
		if (data == null || !data.isObject() || !data.path("ok").isBoolean() || !data.path("ok").booleanValue()
			|| !"ocado-reviewed-v1".equals(data.path("contractVersion").asText())
			|| !"OCD".equals(data.path("vendor").asText())
			|| !mode.equals(data.path("mode").asText()) || !sourceUrl.equals(data.path("requestedUrl").asText()))
			throw fail(ProductContentFailureException.Code.SOURCE_JSON_INVALID);
		String expected = id(sourceUrl), canonical = data.path("sourceUrl").asText();
		try {
			ProductContentUrls.source(VendorType.OCD, canonical);
		} catch (IllegalArgumentException e) {
			throw fail(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		}
		if (!data.path("externalId").isTextual() || !expected.equals(data.path("externalId").textValue())
			|| !expected.equals(id(canonical)) || !data.path("name").isTextual()
			|| data.path("name").asText().isBlank())
			throw fail(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		try {
			Instant at = OffsetDateTime.parse(data.path("scrapedAt").asText()).toInstant(), now = Instant.now();
			if (at.isBefore(now.minusSeconds(300)) || at.isAfter(now.plusSeconds(120)))
				throw new IllegalArgumentException();
		} catch (RuntimeException e) {
			throw fail(ProductContentFailureException.Code.SOURCE_JSON_INVALID);
		}
		if (!"GBP".equals(data.path("currency").asText()) || !data.path("price").isTextual()
			|| !data.path("price").asText().matches("[0-9]{1,7}(?:\\.[0-9]{1,2})?"))
			throw fail(ProductContentFailureException.Code.SOURCE_PRICE_INVALID);
		BigDecimal price = new BigDecimal(data.path("price").asText());
		if (price.signum() <= 0)
			throw fail(ProductContentFailureException.Code.SOURCE_PRICE_INVALID);
		if (!data.path("inStock").isBoolean() || !data.has("stock") || !data.path("stock").isNull())
			throw fail(ProductContentFailureException.Code.SOURCE_STOCK_INVALID);
		if (!data.path("imagesComplete").isBoolean() || !data.path("detailComplete").isBoolean()
			|| data.path("imagesComplete").booleanValue() != content
			|| data.path("detailComplete").booleanValue() != content
			|| !data.path("images").isArray())
			throw fail(ProductContentFailureException.Code.SOURCE_DETAILS_INVALID);
		List<String> images = new ArrayList<>();
		String html = null;
		if (content) {
			for (JsonNode image : data.path("images")) {
				if (!image.isTextual())
					throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
				try {
					images.add(ProductContentUrls.sourceImage(VendorType.OCD, image.textValue()));
				} catch (IllegalArgumentException e) {
					throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
				}
			}
			if (images.isEmpty() || images.size() > 8 || new HashSet<>(images).size() != images.size())
				throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
			if (!data.path("detailHtml").isTextual() || data.path("detailHtml").asText().isBlank()
				|| data.path("detailHtml").asText().length() > 200_000)
				throw fail(ProductContentFailureException.Code.SOURCE_DETAILS_INVALID);
			html = data.path("detailHtml").textValue();
		} else if (!data.path("images").isEmpty() || !data.has("detailHtml") || !data.path("detailHtml").isNull())
			throw fail(ProductContentFailureException.Code.SOURCE_DETAILS_INVALID);
		return new SupplierCatalogClient.Catalog(List.copyOf(images), html, price, "GBP",
			data.path("inStock").booleanValue() ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK, null);
	}

	private static String id(String url) {
		return URI.create(url).getPath().replaceAll("/$", "").replaceAll(".*[/\\-]", "");
	}

	private static ProductContentFailureException fail(ProductContentFailureException.Code code) {
		return new ProductContentFailureException(code);
	}
}
