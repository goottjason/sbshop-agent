package com.sbshop.agent.infrastructure.client.sourcing.content;

import com.fasterxml.jackson.databind.*;
import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.domain.product.enums.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Public FTN GraphQL and Costco OCC documents observed against exact stored product URLs. */
@Component
public class SupplierCatalogClient {
	private final ObjectMapper mapper;
	private final OcadoReviewedCatalogClient ocado;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10)).build();
	private final HttpClient redirectingHttp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(10)).build();

	public SupplierCatalogClient(ObjectMapper mapper) {
		this(mapper, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public SupplierCatalogClient(ObjectMapper mapper, OcadoReviewedCatalogClient ocado) {
		this.mapper = mapper;
		this.ocado = ocado;
	}

	public record Catalog(List<String> images, String html, BigDecimal price, String currency, StockStatus status,
		Integer stock) {
	}

	public Catalog fetch(VendorType vendor, String sourceUrl) {
		ProductContentUrls.source(vendor, sourceUrl);
		if (vendor == VendorType.OCD)
			return ocado.fetch(sourceUrl, true);
		if (vendor == VendorType.FTN) {
			String key = URI.create(sourceUrl).getPath().substring(1).replaceAll("/$", "");
			String query = "{products(filter:{url_key:{eq:\"" + key
				+ "\"}}){items{__typename sku url_key name canonical_url stock_status description{html} media_gallery{__typename url position disabled} image{url} price_range{minimum_price{final_price{value currency}} maximum_price{final_price{value currency}}}}}}";
			return fortnum(read("https://www.fortnumandmason.com/graphql?" + "query="
				+ URLEncoder.encode(query, StandardCharsets.UTF_8)), sourceUrl);
		}
		if (vendor == VendorType.COK) {
			String code = URI.create(sourceUrl).getPath().replaceAll(".*/", "");
			return costco(read("https://www.costco.co.uk/rest/v2/uk/products/" + code + "?fields=FULL"), sourceUrl);
		}
		throw fail(ProductContentFailureException.Code.SOURCE_UNAVAILABLE);
	}

	public Catalog fetchPriceStock(VendorType vendor, String sourceUrl) {
		if (vendor == VendorType.OCD)
			return ocado.fetch(sourceUrl, false);
		if (vendor == VendorType.FTN)
			return fetchFortnumPriceStock(sourceUrl);
		return fetch(vendor, sourceUrl);
	}

	private Catalog fetchFortnumPriceStock(String sourceUrl) {
		try {
			return fortnumPriceStock(readFortnumPriceStock(sourceUrl), sourceUrl);
		} catch (ProductContentFailureException failure) {
			if (failure.code() != ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH)
				throw failure;
			String resolvedUrl = resolveFortnumUrl(sourceUrl);
			if (resolvedUrl.equals(sourceUrl))
				throw failure;
			return fortnumPriceStock(readFortnumPriceStock(resolvedUrl), resolvedUrl);
		}
	}

	public Catalog fortnumPriceStock(JsonNode root, String sourceUrl) {
		JsonNode p = fortnumProduct(root, sourceUrl);
		StockStatus stock = switch (p.path("stock_status").asText()) {
			case "IN_STOCK" -> StockStatus.IN_STOCK;
			case "OUT_OF_STOCK" -> StockStatus.OUT_OF_STOCK;
			default -> throw fail(ProductContentFailureException.Code.SOURCE_STOCK_INVALID);
		};
		var min = p.path("price_range").path("minimum_price").path("final_price");
		var max = p.path("price_range").path("maximum_price").path("final_price");
		BigDecimal price = money(min, "value", "currency");
		if (price.compareTo(money(max, "value", "currency")) != 0)
			throw fail(ProductContentFailureException.Code.SOURCE_VARIANT_UNRESOLVED);
		return new Catalog(List.of(), null, price, "GBP", stock, null);
	}

	public Catalog fortnum(JsonNode root, String sourceUrl) {
		ProductContentUrls.source(VendorType.FTN, sourceUrl);
		JsonNode p = fortnumProduct(root, sourceUrl);
		StockStatus stock = switch (p.path("stock_status").asText()) {
			case "IN_STOCK" -> StockStatus.IN_STOCK;
			case "OUT_OF_STOCK" -> StockStatus.OUT_OF_STOCK;
			default -> throw fail(ProductContentFailureException.Code.SOURCE_STOCK_INVALID);
		};
		var min = p.path("price_range").path("minimum_price").path("final_price");
		var max = p.path("price_range").path("maximum_price").path("final_price");
		BigDecimal price = money(min, "value", "currency");
		if (price.compareTo(money(max, "value", "currency")) != 0)
			throw fail(ProductContentFailureException.Code.SOURCE_VARIANT_UNRESOLVED);
		JsonNode gallery = p.path("media_gallery");
		if (!gallery.isArray() || gallery.isEmpty())
			throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
		TreeMap<Integer, String> ordered = new TreeMap<>();
		for (JsonNode image : gallery) {
			if (!image.path("disabled").isBoolean())
				throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
			if (image.path("disabled").booleanValue())
				continue;
			if (!"ProductImage".equals(image.path("__typename").asText()) || !image.path("position").isIntegralNumber()
				|| ordered.put(image.path("position").intValue(),
					ProductContentUrls.sourceImage(VendorType.FTN, image.path("url").asText())) != null)
				throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
		}
		List<String> images = new ArrayList<>(ordered.values());
		String hero = ProductContentUrls.sourceImage(VendorType.FTN, p.path("image").path("url").asText());
		gallery(images, hero);
		images.remove(hero);
		images.addFirst(hero);
		String html = html(p.path("description").path("html"));
		return new Catalog(List.copyOf(images), html, price, "GBP", stock, null);
	}

	private JsonNode fortnumProduct(JsonNode root, String sourceUrl) {
		ProductContentUrls.source(VendorType.FTN, sourceUrl);
		String key = URI.create(sourceUrl).getPath().substring(1).replaceAll("/$", "");
		JsonNode items = root == null ? null : root.path("data").path("products").path("items");
		if (root == null || root.has("errors") || items == null || !items.isArray() || items.size() != 1)
			throw fail(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		JsonNode p = items.get(0);
		if (!key.equals(p.path("url_key").asText()) || !(key + ".html").equals(p.path("canonical_url").asText())
			|| !p.path("sku").asText().matches("[1-9][0-9]{0,19}") || p.path("name").asText().isBlank())
			throw fail(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		if (!"SimpleProduct".equals(p.path("__typename").asText()))
			throw fail(ProductContentFailureException.Code.SOURCE_VARIANT_UNRESOLVED);
		return p;
	}

	public Catalog costco(JsonNode p, String sourceUrl) {
		ProductContentUrls.source(VendorType.COK, sourceUrl);
		String code = URI.create(sourceUrl).getPath().replaceAll(".*/", "");
		if (p == null || !p.isObject() || !code.equals(p.path("code").asText())
			|| !p.path("url").asText().endsWith("/p/" + code)
			|| p.path("name").asText().isBlank())
			throw fail(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		for (String flag : List.of("configurable", "multiProduct", "multidimensional", "hasOtherOptionsVariants"))
			if (!p.path(flag).isBoolean() || p.path(flag).booleanValue())
				throw fail(ProductContentFailureException.Code.SOURCE_VARIANT_UNRESOLVED);
		if (!p.path("baseOptions").isArray() || !p.path("baseOptions").isEmpty())
			throw fail(ProductContentFailureException.Code.SOURCE_VARIANT_UNRESOLVED);
		String status = p.path("stock").path("stockLevelStatus").asText();
		StockStatus stock = switch (status) {
			case "inStock" -> StockStatus.IN_STOCK;
			case "outOfStock" -> StockStatus.OUT_OF_STOCK;
			default -> throw fail(ProductContentFailureException.Code.SOURCE_STOCK_INVALID);
		};
		if (stock == StockStatus.IN_STOCK
			&& (!p.path("purchasable").isBoolean() || !p.path("purchasable").booleanValue()))
			throw fail(ProductContentFailureException.Code.SOURCE_STOCK_INVALID);
		if (!p.path("hidePriceValue").isBoolean() || p.path("hidePriceValue").booleanValue())
			throw fail(ProductContentFailureException.Code.SOURCE_UNAVAILABLE);
		BigDecimal price = money(p.path("price"), "value", "currencyIso");
		JsonNode gallery = p.path("images");
		if (!gallery.isArray())
			throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
		TreeMap<Integer, String> ordered = new TreeMap<>();
		Set<Integer> indexes = new HashSet<>();
		for (JsonNode image : gallery) {
			if (!"GALLERY".equals(image.path("imageType").asText()))
				continue;
			if (!image.path("galleryIndex").isIntegralNumber())
				throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
			int index = image.path("galleryIndex").intValue();
			indexes.add(index);
			if ("superZoom".equals(image.path("format").asText())) {
				String url = image.path("url").asText();
				if (url.startsWith("/medias/"))
					url = "https://www.costco.co.uk" + url;
				if (ordered.put(index, ProductContentUrls.sourceImage(VendorType.COK, url)) != null)
					throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
			}
		}
		if (!ordered.keySet().equals(indexes) || !ordered.containsKey(0))
			throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
		List<String> images = new ArrayList<>(ordered.values());
		gallery(images, ordered.get(0));
		// OCC stockLevel may be a sentinel when stockLevelStatus is inStock. Do not invent real inventory from it.
		return new Catalog(List.copyOf(images), html(p.path("description")), price, "GBP", stock, null);
	}

	private static void gallery(List<String> images, String hero) {
		if (images.isEmpty() || images.size() > 8 || new HashSet<>(images).size() != images.size()
			|| !images.contains(hero))
			throw fail(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
	}

	private static String html(JsonNode html) {
		if (!html.isTextual() || html.asText().isBlank() || html.asText().length() > 1_000_000)
			throw fail(ProductContentFailureException.Code.SOURCE_DETAILS_INVALID);
		return html.asText();
	}

	private static BigDecimal money(JsonNode node, String amount, String currency) {
		if (!"GBP".equals(node.path(currency).asText()) || !node.path(amount).isNumber()
			|| node.path(amount).decimalValue().signum() <= 0)
			throw fail(ProductContentFailureException.Code.SOURCE_UNAVAILABLE);
		return node.path(amount).decimalValue();
	}

	private static ProductContentFailureException fail(ProductContentFailureException.Code code) {
		return new ProductContentFailureException(code);
	}

	private JsonNode readFortnumPriceStock(String sourceUrl) {
		String key = URI.create(sourceUrl).getPath().substring(1).replaceAll("/$", "");
		String query = "{products(filter:{url_key:{eq:\"" + key
			+ "\"}}){items{__typename sku url_key name canonical_url stock_status price_range{minimum_price{final_price{value currency}} maximum_price{final_price{value currency}}}}}}";
		return read("https://www.fortnumandmason.com/graphql?query="
			+ URLEncoder.encode(query, StandardCharsets.UTF_8));
	}

	private String resolveFortnumUrl(String sourceUrl) {
		try {
			var request = HttpRequest.newBuilder(URI.create(sourceUrl)).timeout(Duration.ofSeconds(30))
				.header("User-Agent", "Mozilla/5.0").GET().build();
			com.sbshop.agent.core.application.product.source.ProductSourceHttpGuard.check();
			var response = redirectingHttp.send(request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() == 429)
				throw new ProductContentThrottledException(
					com.sbshop.agent.infrastructure.client.smartstore.client.InspectionRetryAfter
						.parse(response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
			if (response.statusCode() == 404 || response.statusCode() == 410)
				throw ProductContentFailureException.http(response.statusCode());
			if (response.statusCode() != 200)
				throw fail(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
			String resolved = response.uri().toString();
			ProductContentUrls.source(VendorType.FTN, resolved);
			return resolved;
		} catch (ProductContentFailureException | ProductContentThrottledException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw fail(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		} catch (Exception e) {
			throw fail(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		}
	}

	private JsonNode read(String url) {
		try {
			var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
				.header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").GET().build();
			com.sbshop.agent.core.application.product.source.ProductSourceHttpGuard.check();
			var response = http.send(request,
				info -> info.statusCode() == 200 ? new IherbProductContentSource.LimitedBodySubscriber(2_000_000)
					: HttpResponse.BodySubscribers.replacing(new byte[0]));
			if (response.statusCode() == 429)
				throw new ProductContentThrottledException(
					com.sbshop.agent.infrastructure.client.smartstore.client.InspectionRetryAfter
						.parse(response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
			if (response.statusCode() != 200)
				throw ProductContentFailureException.http(response.statusCode());
			return mapper.readTree(response.body());
		} catch (ProductContentFailureException | ProductContentThrottledException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw fail(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		} catch (Exception e) {
			throw fail(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		}
	}
}
