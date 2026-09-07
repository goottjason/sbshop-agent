package com.sbshop.agent.infrastructure.client.sourcing.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.application.product.source.ProductSourceHttpGuard;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OcadoReviewedCatalogClientTest {
	final ObjectMapper mapper = new ObjectMapper();
	final String source = "https://www.ocado.com/products/cirio-tomato-puree-80259011";
	final OcadoReviewedCatalogClient client = new OcadoReviewedCatalogClient(mapper, "http://localhost:1");

	ObjectNode fixture() throws Exception {
		var data = (ObjectNode)mapper
			.readTree(getClass().getResourceAsStream("/sourcing/ocado-reviewed-2026-09-08-cirio.json"));
		data.put("scrapedAt", Instant.now().toString());
		return data;
	}

	@Test
	void actualBrowserEvidenceRetainsWholeProductDetailAndDoesNotInventRealInventory() throws Exception {
		var observed = client.parse(fixture(), source, true);
		assertThat(observed.price()).isEqualByComparingTo("1.50");
		assertThat(observed.currency()).isEqualTo("GBP");
		assertThat(observed.status()).isEqualTo(StockStatus.IN_STOCK);
		assertThat(observed.stock()).isNull();
		assertThat(observed.images()).hasSize(1);
		assertThat(observed.html()).contains("Ingredients", "Nutritional data", "Storage")
			.doesNotContain("Customer reviews", "<script");
	}

	@Test
	void wrongRequestedUrlFinalUrlIdentityModeOrContractCannotBeAccepted() throws Exception {
		for (String key : new String[] {"requestedUrl", "sourceUrl", "externalId", "mode", "contractVersion"}) {
			var data = fixture();
			data.put(key, "other");
			assertThatThrownBy(() -> client.parse(data, source, true))
				.isInstanceOf(ProductContentFailureException.class);
		}
		var stale = fixture().put("scrapedAt", Instant.now().minusSeconds(601).toString());
		assertThatThrownBy(() -> client.parse(stale, source, true)).hasMessageContaining("SOURCE_JSON_INVALID");
	}

	@Test
	void noCurrencyAvailabilityQuantityOrNumericCoercion() throws Exception {
		var currency = fixture().put("currency", "USD");
		assertThatThrownBy(() -> client.parse(currency, source, true)).hasMessageContaining("SOURCE_PRICE_INVALID");
		var price = fixture().put("price", 1.5);
		assertThatThrownBy(() -> client.parse(price, source, true)).hasMessageContaining("SOURCE_PRICE_INVALID");
		var availability = fixture().put("inStock", "true");
		assertThatThrownBy(() -> client.parse(availability, source, true)).hasMessageContaining("SOURCE_STOCK_INVALID");
		var stock = fixture().put("stock", 100);
		assertThatThrownBy(() -> client.parse(stock, source, true)).hasMessageContaining("SOURCE_STOCK_INVALID");
		var absent = fixture();
		absent.remove("inStock");
		assertThatThrownBy(() -> client.parse(absent, source, true)).hasMessageContaining("SOURCE_STOCK_INVALID");
	}

	@Test
	void partialImagesOrDescriptionCannotBePresentedAsComplete() throws Exception {
		var data = fixture().put("imagesComplete", false);
		assertThatThrownBy(() -> client.parse(data, source, true)).hasMessageContaining("SOURCE_DETAILS_INVALID");
		data.put("imagesComplete", true).putArray("images");
		assertThatThrownBy(() -> client.parse(data, source, true)).hasMessageContaining("SOURCE_IMAGES_INVALID");
		var detail = fixture().put("detailHtml", "");
		assertThatThrownBy(() -> client.parse(detail, source, true)).hasMessageContaining("SOURCE_DETAILS_INVALID");
	}

	@Test
	void priceStockModeCanSucceedWithoutContentAndStockCountRemainsUnknown() throws Exception {
		var data = fixture().put("mode", "PRICE_STOCK").put("imagesComplete", false).put("detailComplete", false)
			.putNull("detailHtml");
		data.putArray("images");
		data.put("inStock", false);
		var observed = client.parse(data, source, false);
		assertThat(observed.status()).isEqualTo(StockStatus.OUT_OF_STOCK);
		assertThat(observed.stock()).isNull();
		assertThat(observed.html()).isNull();
		assertThat(observed.images()).isEmpty();
	}

	@Test
	void ocadoUsesSeparateReviewedModesWhileOtherSuppliersStillUseTheirVerifiedCatalogs() {
		var ocado = mock(OcadoReviewedCatalogClient.class);
		var catalog = new SupplierCatalogClient(mapper, ocado);
		catalog.fetch(VendorType.OCD, source);
		catalog.fetchPriceStock(VendorType.OCD, source);
		verify(ocado).fetch(source, true);
		verify(ocado).fetch(source, false);
		verifyNoMoreInteractions(ocado);
	}

	@Test
	void onlyActualOcadoProductAndGalleryPathsAreAccepted() {
		assertThat(ProductContentUrls.supports(VendorType.OCD)).isTrue();
		assertThat(ProductContentUrls.source(VendorType.OCD, source)).isEqualTo(source);
		assertThat(ProductContentUrls.source(VendorType.OCD,
			"https://www.ocado.com/products/cirio-italian-tomato-puree/80259011")).endsWith("/80259011");
		for (String url : new String[] {source + "?variant=1", source + "#x",
			source.replace("www.ocado.com", "www.ocado.com.evil.example"),
			source.replace("www.ocado.com", "user@www.ocado.com"), "https://www.ocado.com/products/no-id"})
			assertThatThrownBy(() -> ProductContentUrls.source(VendorType.OCD, url))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
			() -> ProductContentUrls.sourceImage(VendorType.OCD, "https://www.ocado.com/redirect?image=1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
			() -> ProductContentUrls.sourceImage(VendorType.OCD, "https://evil.example/images-v3/a/b/500x500.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void transportPassesModeAndPropagates429RetryAfterWithoutRetryOrFalseSuccess() throws Exception {
		var calls = new AtomicInteger();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/scrape/reviewed/ocado", exchange -> {
			calls.incrementAndGet();
			var request = mapper.readTree(exchange.getRequestBody());
			assertThat(request.path("mode").asText()).isEqualTo("PRICE_STOCK");
			assertThat(request.path("url").asText()).isEqualTo(source);
			exchange.getResponseHeaders().add("Retry-After", "600");
			byte[] body = "{\"ok\":false,\"errorCode\":\"SOURCE_THROTTLED\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(429, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			var transport = new OcadoReviewedCatalogClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort());
			Instant now = Instant.now();
			assertThatThrownBy(() -> transport.fetch(source, false)).isInstanceOfSatisfying(
				ProductContentThrottledException.class,
				failure -> assertThat(failure.retryAfter()).isBetween(now.plusSeconds(599), now.plusSeconds(610)));
			assertThat(calls).hasValue(1);
			assertThatThrownBy(() -> ProductSourceHttpGuard.scoped(() -> {
				throw new ProductContentThrottledException(now.plusSeconds(1200));
			},
				() -> transport.fetch(source, false))).isInstanceOf(ProductContentThrottledException.class);
			assertThat(calls).hasValue(1);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void blockedHttpWithProductLookingBodyIsNeverAcceptedAndSafeContractFailureIsPreserved() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var httpStatus = new AtomicInteger(403);
		server.createContext("/scrape/reviewed/ocado", exchange -> {
			byte[] body = "{\"ok\":false,\"errorCode\":\"SOURCE_IDENTITY_MISMATCH\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(httpStatus.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			var transport = new OcadoReviewedCatalogClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort());
			assertThatThrownBy(() -> transport.fetch(source, true)).hasMessageContaining("HTTP 403");
			httpStatus.set(422);
			assertThatThrownBy(() -> transport.fetch(source, true)).hasMessageContaining("SOURCE_IDENTITY_MISMATCH");
		} finally {
			server.stop(0);
		}
	}
}
