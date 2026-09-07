package com.sbshop.agent.infrastructure.client.sourcing.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Public Shopify Ajax contract observed at the named VTB store; no handle renaming or variant guessing. */
@Component
public class VitabioticsCatalogClient {
	private final ObjectMapper mapper;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10)).build();

	public VitabioticsCatalogClient(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public record Product(JsonNode data, JsonNode variant, String handle) {
	}

	public Product fetch(String sourceUrl) {
		ProductContentUrls.source(VendorType.VTB, sourceUrl);
		String handle = handle(sourceUrl);
		return parse(read("https://www.vitabiotics.com/products/" + handle + ".js"), sourceUrl);
	}

	public String currency() {
		JsonNode currency = read("https://www.vitabiotics.com/cart.js").path("currency");
		if (!currency.isTextual() || !"GBP".equals(currency.textValue()))
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		return "GBP";
	}

	public Product parse(JsonNode data, String sourceUrl) {
		ProductContentUrls.source(VendorType.VTB, sourceUrl);
		String handle = handle(sourceUrl);
		if (data == null || !data.isObject() || !data.path("id").isIntegralNumber()
			|| data.path("id").longValue() <= 0 || !handle.equals(data.path("handle").asText())
			|| !("/products/" + handle).equals(data.path("url").asText())
			|| !data.path("title").isTextual() || data.path("title").asText().isBlank())
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		JsonNode variants = data.path("variants");
		if (!variants.isArray() || variants.isEmpty() || variants.size() >= 250)
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_VARIANT_UNRESOLVED);
		String query = URI.create(sourceUrl).getRawQuery();
		String selected = query == null ? null : query.substring("variant=".length());
		JsonNode variant = null;
		Set<String> identifiers = new HashSet<>();
		for (JsonNode candidate : variants) {
			if (!candidate.path("id").isIntegralNumber() || candidate.path("id").longValue() <= 0
				|| !identifiers.add(candidate.path("id").asText()))
				throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_VARIANT_UNRESOLVED);
			if (selected != null && selected.equals(candidate.path("id").asText())
				|| selected == null && variants.size() == 1)
				variant = candidate;
		}
		if (variant == null)
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_VARIANT_UNRESOLVED);
		return new Product(data, variant, handle);
	}

	private static String handle(String sourceUrl) {
		String path = URI.create(sourceUrl).getPath().replaceAll("/$", "");
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private JsonNode read(String url) {
		try {
			var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
				.header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").GET().build();
			com.sbshop.agent.core.application.product.source.ProductSourceHttpGuard.check();
			var response = http.send(request, info -> info.statusCode() == 200
				? new IherbProductContentSource.LimitedBodySubscriber(1_000_000)
				: HttpResponse.BodySubscribers.replacing(new byte[0]));
			if (response.statusCode() == 429)
				throw new ProductContentThrottledException(
					com.sbshop.agent.infrastructure.client.smartstore.client.InspectionRetryAfter.parse(
						response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
			if (response.statusCode() != 200)
				throw ProductContentFailureException.http(response.statusCode());
			return mapper.readTree(response.body());
		} catch (ProductContentFailureException | ProductContentThrottledException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		} catch (Exception e) {
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		}
	}
}
