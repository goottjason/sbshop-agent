package com.sbshop.agent.infrastructure.client.sourcing.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.infrastructure.client.fx.FxRateClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VitabioticsReviewedSourceTest {
	ObjectMapper mapper = new ObjectMapper();
	VitabioticsCatalogClient parser = new VitabioticsCatalogClient(mapper);
	static final String URL = "https://www.vitabiotics.com/collections/all-vitabiotics-products/products/wellkid-multi-vitamin-liquid";

	ObjectNode data() throws Exception {
		try (var stream = getClass().getResourceAsStream("/sourcing/vtb-content-2026-09-08-wellkid.json")) {
			return (ObjectNode)mapper.readTree(stream);
		}
	}

	@Test
	void actualPublicContractContainsFourImagesAndDescriptionWithExactSingleVariant() throws Exception {
		var product = parser.parse(data(), URL);
		assertThat(product.variant().path("id").asText()).isEqualTo("29084133851205");
		assertThat(product.data().path("images")).hasSize(4);
		assertThat(product.data().path("description").asText()).isNotBlank();
		var catalog = mock(VitabioticsCatalogClient.class);
		when(catalog.fetch(URL)).thenReturn(product);
		var hosting = mock(IherbProductContentSource.class);
		when(hosting.prepare(anyList(), anyString(), any())).thenAnswer(
			i -> new ProductContentSource.Fetch(i.getArgument(0), List.of(), i.getArgument(1), false, true, List.of()));
		var fetched = new VitabioticsProductContentSource(catalog, hosting).fetch(URL);
		assertThat(fetched.sourceImages()).hasSize(4)
			.allMatch(url -> url.startsWith("https://cdn.shopify.com/s/files/1/0027/7263/1621/"));
		assertThat(fetched.sourceImages().getFirst())
			.isEqualTo("https:" + product.data().path("featured_image").asText());
	}

	@Test
	void missingOrForeignFeaturedImageNeverReplacesExistingImages() throws Exception {
		var catalog = mock(VitabioticsCatalogClient.class);
		var hosting = mock(IherbProductContentSource.class);
		ObjectNode d = data();
		d.put("featured_image", "https://cdn.shopify.com/s/files/1/9999/wrong.jpg");
		when(catalog.fetch(URL)).thenReturn(parser.parse(d, URL));
		assertThatThrownBy(() -> new VitabioticsProductContentSource(catalog, hosting).fetch(URL))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(hosting);
	}

	@Test
	void wrongHandleOrCanonicalUrlCannotBeAcceptedAsRequestedProduct() throws Exception {
		final var d = data();
		d.put("handle", "different");
		assertThatThrownBy(() -> parser.parse(d, URL)).isInstanceOf(ProductContentFailureException.class);
		final var wrong = data();
		wrong.put("url", "/products/different");
		assertThatThrownBy(() -> parser.parse(wrong, URL)).isInstanceOf(ProductContentFailureException.class);
	}

	@Test
	void explicitVariantResolvesQuantityButMultipleVariantGalleryIsNotAssumedComplete() throws Exception {
		var d = data();
		((ArrayNode)d.path("variants")).add(d.path("variants").get(0).deepCopy());
		((ObjectNode)d.path("variants").get(1)).put("id", 29084133851206L);
		assertThatThrownBy(() -> parser.parse(d, URL)).isInstanceOf(ProductContentFailureException.class);
		var specific = parser.parse(d, URL + "?variant=29084133851206");
		assertThat(specific.variant().path("id").asText()).isEqualTo("29084133851206");
		var catalog = mock(VitabioticsCatalogClient.class);
		when(catalog.fetch(anyString())).thenReturn(specific);
		assertThatThrownBy(
			() -> new VitabioticsProductContentSource(catalog, mock(IherbProductContentSource.class)).fetch(URL))
			.isInstanceOf(ProductContentFailureException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://vitabiotics.com.evil.example/products/x", "http://www.vitabiotics.com/products/x",
		"https://www.vitabiotics.com@127.0.0.1/products/x", "https://www.vitabiotics.com:8443/products/x",
		"https://www.vitabiotics.com/products/x?variant=1&redirect=http://127.0.0.1",
		"https://www.vitabiotics.com/products/../../x"})
	void invalidOrPrivateUrlsAreRejectedBeforeTransport(String url) {
		assertThatThrownBy(() -> ProductContentUrls.source(VendorType.VTB, url))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void malformedAvailabilityDoesNotBecomeOutOfStockAndMissingActualStockIsNeverInvented() throws Exception {
		var catalog = mock(VitabioticsCatalogClient.class);
		var fx = mock(FxRateClient.class);
		ObjectNode d = data();
		((ObjectNode)d.path("variants").get(0)).put("available", "false");
		when(catalog.fetch(URL)).thenReturn(parser.parse(d, URL));
		var source = new ProductSourceObservationClient(mapper, catalog, fx, null);
		assertThatThrownBy(() -> source.fetch(VendorType.VTB, URL)).isInstanceOf(ProductContentFailureException.class);
		when(catalog.fetch(URL)).thenReturn(parser.parse(data(), URL));
		when(catalog.currency()).thenReturn("GBP");
		when(fx.toKrw("GBP")).thenReturn(new BigDecimal("1800"));
		var fetched = source.fetch(VendorType.VTB, URL);
		assertThat(fetched.goodsPriceKrw()).isEqualByComparingTo("13410");
		assertThat(fetched.stock()).isNull();
		assertThat(fetched.stockStatus()).isEqualTo(StockStatus.IN_STOCK);
	}

	@Test
	void failedCurrencyOrFxKeepsObservedStockButNoPriceAnd429CarriesCooldown() throws Exception {
		var catalog = mock(VitabioticsCatalogClient.class);
		when(catalog.fetch(URL)).thenReturn(parser.parse(data(), URL));
		var fx = mock(FxRateClient.class);
		when(fx.toKrw("GBP")).thenThrow(new IllegalStateException("no rate"));
		var source = new ProductSourceObservationClient(mapper, catalog, fx, null);
		assertThat(source.fetch(VendorType.VTB, URL).goodsPriceKrw()).isNull();
		assertThat(source.fetch(VendorType.VTB, URL).stockStatus()).isEqualTo(StockStatus.IN_STOCK);
		Instant next = Instant.now().plusSeconds(900);
		when(catalog.fetch(URL)).thenThrow(new ProductContentThrottledException(next));
		assertThatThrownBy(() -> source.fetch(VendorType.VTB, URL)).isInstanceOfSatisfying(
			ProductContentThrottledException.class,
			failure -> assertThat(failure.retryAfter()).isEqualTo(next));
	}

	@Test
	void ihbOnlyVerifiedKrwPriceAndExplicitBooleanBecomeObservations() throws Exception {
		var source = new ProductSourceObservationClient(mapper, null, null, null);
		ObjectNode d = (ObjectNode)mapper.readTree(
			"{\"id\":18566,\"url\":\"/pr/example/18566\",\"isAvailableToPurchase\":true,\"discountPrice\":\"₩49,886\",\"discountPriceAmount\":49886}");
		assertThat(source.parseIherb(d, "18566").goodsPriceKrw()).isEqualByComparingTo("49886");
		assertThat(source.parseIherb(d, "18566").stock()).isNull();
		d.put("discountPrice", "$49.00");
		assertThat(source.parseIherb(d, "18566").goodsPriceKrw()).isNull();
		d.put("isAvailableToPurchase", "false");
		assertThatThrownBy(() -> source.parseIherb(d, "18566")).isInstanceOf(ProductContentFailureException.class);
		assertThatThrownBy(() -> source.parseIherb(d, "99999")).isInstanceOf(ProductContentFailureException.class);
	}
}
