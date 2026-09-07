package com.sbshop.agent.infrastructure.client.sourcing.content;

import com.fasterxml.jackson.databind.*;
import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.application.product.source.*;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.infrastructure.client.fx.FxRateClient;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Strict observations for the reviewed flow. Legacy crawler defaults and retry loops are not reused. */
@Component
public class ProductSourceObservationClient implements ProductSourceObservationSource {
	private final ObjectMapper mapper;
	private final VitabioticsCatalogClient vtb;
	private final FxRateClient fx;
	private final SupplierCatalogClient catalog;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10)).build();

	public ProductSourceObservationClient(ObjectMapper mapper, VitabioticsCatalogClient vtb, FxRateClient fx,
		SupplierCatalogClient catalog) {
		this.mapper = mapper;
		this.vtb = vtb;
		this.fx = fx;
		this.catalog = catalog;
	}

	@Override
	public ProductSourceData.Observed fetch(VendorType vendor, String sourceUrl) {
		ProductContentUrls.source(vendor, sourceUrl);
		if (vendor == VendorType.VTB)
			return vitabiotics(sourceUrl);
		if (vendor == VendorType.FTN || vendor == VendorType.COK || vendor == VendorType.OCD) {
			var observed = catalog.fetchPriceStock(vendor, sourceUrl);
			ProductSourceData.PricingEvidence pricing = null;
			List<String> notices = new ArrayList<>();
			try {
				pricing = pricing(observed.price(), observed.currency(), fx.toKrw(observed.currency()), notices);
			} catch (ProductContentThrottledException e) {
				throw e;
			} catch (Exception e) {
				notices.add("최신 환율을 확인하지 못했습니다. 가격은 유지합니다.");
			}
			return new ProductSourceData.Observed(pricing == null ? null : pricing.goodsPriceKrw(),
				pricing == null ? null : pricing.normalizedExchangeRate(), observed.currency(), observed.status(),
				observed.stock(), notices, pricing);
		}
		if (vendor != VendorType.IHB)
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_UNAVAILABLE);
		String id = URI.create(sourceUrl).getPath().replaceAll("/$", "").replaceAll(".*/", "");
		try {
			var request = iherbRequest(id);
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
			return parseIherb(mapper.readTree(response.body()), id);
		} catch (ProductContentFailureException | ProductContentThrottledException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		} catch (Exception e) {
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		}
	}

	static HttpRequest iherbRequest(String id) {
		if (id == null || !id.matches("[0-9]+"))
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		// Same catalog request headers as the already verified IHB content path.
		return HttpRequest.newBuilder(URI.create("https://catalog.app.iherb.com/product/" + id))
			.timeout(Duration.ofSeconds(30)).header("User-Agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
			.header("Accept", "application/json").header("Accept-Language", "en-US,en;q=0.9")
			.header("Referer", "https://www.iherb.com/").GET().build();
	}

	public ProductSourceData.Observed parseIherb(JsonNode data, String expectedId) {
		if (data == null || !data.isObject() || !data.path("id").isIntegralNumber()
			|| !expectedId.equals(data.path("id").asText()) || !data.path("url").isTextual())
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		String observedUrl = data.path("url").textValue();
		if (observedUrl.startsWith("/"))
			observedUrl = "https://www.iherb.com" + observedUrl;
		try {
			ProductContentUrls.source(observedUrl);
		} catch (IllegalArgumentException e) {
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		}
		if (!URI.create(observedUrl).getPath().replaceAll("/$", "").endsWith("/" + expectedId))
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IDENTITY_MISMATCH);
		if (!data.path("isAvailableToPurchase").isBoolean())
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_STOCK_INVALID);
		List<String> notices = new ArrayList<>();
		BigDecimal price = null;
		for (String field : List.of("discountPrice", "listPrice")) {
			var amount = data.path(field + "Amount");
			var display = data.path(field);
			if (amount.isNumber() && amount.decimalValue().signum() > 0 && display.isTextual()
				&& display.textValue().strip().startsWith("₩")) {
				price = amount.decimalValue();
				break;
			}
		}
		if (price == null)
			notices.add("IHB의 양수 가격과 원화 통화 표기를 함께 확인하지 못했습니다. 가격은 유지합니다.");
		Integer stock = null;
		if (data.hasNonNull("stockQuantity")) {
			var q = data.path("stockQuantity");
			if (q.isIntegralNumber() && q.canConvertToInt() && q.intValue() >= 0 && q.intValue() <= 999999)
				stock = q.intValue();
			else
				notices.add("실재고 수량 형식이 유효하지 않습니다. 실재고는 유지합니다.");
		}
		return new ProductSourceData.Observed(price, BigDecimal.ONE, "KRW",
			data.path("isAvailableToPurchase").booleanValue() ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK, stock,
			notices, price == null ? null : new ProductSourceData.PricingEvidence(price, "KRW", BigDecimal.ONE,
				BigDecimal.ONE, price));
	}

	private ProductSourceData.Observed vitabiotics(String url) {
		var variant = vtb.fetch(url).variant();
		if (!variant.path("available").isBoolean())
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_STOCK_INVALID);
		ProductSourceData.PricingEvidence pricing = null;
		List<String> notices = new ArrayList<>();
		if (variant.path("price").isIntegralNumber() && variant.path("price").decimalValue().signum() > 0) {
			try {
				vtb.currency(); // Shopify prices use the presentment currency; the existing GBP assumption is verified.
				pricing = pricing(variant.path("price").decimalValue().movePointLeft(2), "GBP", fx.toKrw("GBP"),
					notices);
			} catch (ProductContentThrottledException e) {
				throw e;
			} catch (Exception e) {
				notices.add("VTB 가격 통화 또는 최신 환율을 확인하지 못했습니다. 가격은 유지합니다.");
			}
		} else
			notices.add("VTB 규격의 양수 정수 가격을 확인하지 못했습니다. 가격은 유지합니다.");
		return new ProductSourceData.Observed(pricing == null ? null : pricing.goodsPriceKrw(),
			pricing == null ? null : pricing.normalizedExchangeRate(), "GBP", variant.path("available").booleanValue()
				? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK, null, notices, pricing);
	}

	private static ProductSourceData.PricingEvidence pricing(BigDecimal sourcePrice, String currency,
		BigDecimal observedRate, List<String> notices) {
		if (sourcePrice == null || sourcePrice.signum() <= 0 || observedRate == null || observedRate.signum() <= 0)
			throw new IllegalArgumentException();
		BigDecimal rate = observedRate.setScale(2, RoundingMode.HALF_UP);
		if (rate.signum() <= 0 || rate.compareTo(new BigDecimal("99999999.99")) > 0)
			throw new IllegalArgumentException();
		if (rate.compareTo(observedRate) != 0)
			notices.add("수집 원본 환율 " + observedRate.toPlainString() + " → " + rate.toPlainString()
				+ " (소수 2자리 반올림). 상품 원가와 배송비는 이 저장 가능한 환율로 계산했습니다.");
		return new ProductSourceData.PricingEvidence(sourcePrice, currency, observedRate, rate,
			sourcePrice.multiply(rate).setScale(0, RoundingMode.HALF_UP));
	}
}
