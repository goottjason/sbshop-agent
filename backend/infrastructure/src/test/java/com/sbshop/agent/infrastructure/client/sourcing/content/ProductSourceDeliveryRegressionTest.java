package com.sbshop.agent.infrastructure.client.sourcing.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.infrastructure.client.fx.FxRateClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProductSourceDeliveryRegressionTest {
	private final ObjectMapper mapper = new ObjectMapper();

	@ParameterizedTest
	@EnumSource(value = VendorType.class, names = {"FTN", "COK", "OCD"})
	void supplierFxIsRoundedBeforeGoodsConversionAndExactEvidenceIsRetained(VendorType vendor) {
		String url = switch (vendor) {
			case FTN -> "https://www.fortnumandmason.com/fortnum-s-fig-fennel-chutney-250g";
			case COK ->
				"https://www.costco.co.uk/Grocery-Household/Tea-Coffee-Hot-Drinks/Tea-Coffee/Lavazza-Qualita-Rossa-Coffee-Beans-1kg/p/139465";
			default -> "https://www.ocado.com/products/cirio-tomato-puree-80259011";
		};
		var catalog = mock(SupplierCatalogClient.class);
		when(catalog.fetchPriceStock(vendor, url)).thenReturn(new SupplierCatalogClient.Catalog(List.of(), null,
			new BigDecimal("999.99"), "GBP", StockStatus.IN_STOCK, null));
		var fx = mock(FxRateClient.class);
		when(fx.toKrw("GBP")).thenReturn(new BigDecimal("1822.555001"));
		var observed = new ProductSourceObservationClient(mapper, null, fx, catalog).fetch(vendor, url);
		assertThat(observed.exchangeRate()).isEqualByComparingTo("1822.56");
		assertThat(observed.goodsPriceKrw()).isEqualByComparingTo("1822542");
		assertThat(observed.pricingEvidence().observedExchangeRate()).isEqualByComparingTo("1822.555001");
		assertThat(observed.pricingEvidence().sourcePrice()).isEqualByComparingTo("999.99");
		assertThat(observed.pricingEvidence().normalizedExchangeRate()).isEqualByComparingTo("1822.56");
		assertThat(observed.notices()).anyMatch(n -> n.contains("1822.555001 → 1822.56"));
		assertThat(observed.stock()).isNull();
	}

	@Test
	void vtbUsesSameStoredFxForGoodsAndDownstreamShippingWithActualProductionRate() throws Exception {
		String url = "https://www.vitabiotics.com/products/wellkid-multi-vitamin-liquid";
		ObjectNode data = (ObjectNode)mapper.readTree(getClass()
			.getResourceAsStream("/sourcing/vtb-content-2026-09-08-wellkid.json"));
		((ObjectNode)data.path("variants").get(0)).put("price", 99999);
		var catalog = mock(VitabioticsCatalogClient.class);
		when(catalog.fetch(url)).thenReturn(new VitabioticsCatalogClient(mapper).parse(data, url));
		when(catalog.currency()).thenReturn("GBP");
		var fx = mock(FxRateClient.class);
		when(fx.toKrw("GBP")).thenReturn(new BigDecimal("1822.551899"));
		var observed = new ProductSourceObservationClient(mapper, catalog, fx, null).fetch(VendorType.VTB, url);
		assertThat(observed.exchangeRate()).isEqualByComparingTo("1822.55");
		assertThat(observed.goodsPriceKrw()).isEqualByComparingTo("1822532");
		assertThat(observed.pricingEvidence().observedExchangeRate()).isEqualByComparingTo("1822.551899");
		assertThat(observed.pricingEvidence().sourcePrice()).isEqualByComparingTo("999.99");
		assertThat(observed.notices()).anyMatch(n -> n.contains("1822.551899 → 1822.55"));
	}

	@Test
	void fxThatRoundsToZeroOrExceedsTheDatabaseColumnCannotGeneratePrice() {
		String url = "https://www.costco.co.uk/Grocery-Household/Tea-Coffee-Hot-Drinks/Tea-Coffee/Lavazza-Qualita-Rossa-Coffee-Beans-1kg/p/139465";
		var catalog = mock(SupplierCatalogClient.class);
		when(catalog.fetchPriceStock(VendorType.COK, url)).thenReturn(new SupplierCatalogClient.Catalog(List.of(), null,
			BigDecimal.TEN, "GBP", StockStatus.IN_STOCK, null));
		var fx = mock(FxRateClient.class);
		for (String rate : List.of("0.0049", "99999999.999")) {
			when(fx.toKrw("GBP")).thenReturn(new BigDecimal(rate));
			var observed = new ProductSourceObservationClient(mapper, null, fx, catalog).fetch(VendorType.COK, url);
			assertThat(observed.goodsPriceKrw()).isNull();
			assertThat(observed.exchangeRate()).isNull();
			assertThat(observed.stockStatus()).isEqualTo(StockStatus.IN_STOCK);
			assertThat(observed.stock()).isNull();
		}
	}

	@Test
	void ihbProductionResponseHasExactIdentityKrwAndBooleanWithVerifiedRequestHeaders() throws Exception {
		var request = ProductSourceObservationClient.iherbRequest("18566");
		assertThat(request.uri().toString()).isEqualTo("https://catalog.app.iherb.com/product/18566");
		assertThat(request.method()).isEqualTo("GET");
		assertThat(request.timeout()).contains(Duration.ofSeconds(30));
		assertThat(request.headers().firstValue("User-Agent").orElseThrow())
			.contains("AppleWebKit/537.36", "Chrome/120.0.0.0", "Safari/537.36");
		assertThatThrownBy(() -> ProductSourceObservationClient.iherbRequest("18566?currency=USD"))
			.hasMessageContaining("SOURCE_IDENTITY_MISMATCH");
		var data = mapper.readTree(getClass().getResourceAsStream("/sourcing/ihb-reviewed-price-2026-09-08.json"));
		var observed = new ProductSourceObservationClient(mapper, null, null, null).parseIherb(data, "18566");
		assertThat(observed.goodsPriceKrw()).isEqualByComparingTo("49886");
		assertThat(observed.exchangeRate()).isEqualByComparingTo("1");
		assertThat(observed.pricingEvidence().currency()).isEqualTo("KRW");
		assertThat(observed.stockStatus()).isEqualTo(StockStatus.IN_STOCK);
		assertThat(observed.stock()).isNull();
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({"0,0,true", "8,3,false", "0,3,false", "8,0,false",
		"null,0,false", "0,null,false", "0.0,0,false", "2147483648,0,false", "'\"0\"',0,false"})
	void explicitIntegerPromoCodesAreRequiredForCouponExclusion(String type, String display, boolean excluded)
		throws Exception {
		ObjectNode data = (ObjectNode)mapper.readTree(getClass()
			.getResourceAsStream("/sourcing/ihb-reviewed-price-2026-09-08.json"));
		data.set("discountType", mapper.readTree(type));
		data.set("discountDisplayType", mapper.readTree(display));
		var observed = new ProductSourceObservationClient(mapper, null, null, null).parseIherb(data, "18566");
		assertThat(observed.pricingEvidence().iherbDiscount().excludesCoupon()).isEqualTo(excluded);
		var restored = mapper.readValue(mapper.writeValueAsString(observed),
			com.sbshop.agent.core.application.product.source.ProductSourceData.Observed.class);
		assertThat(restored.pricingEvidence().iherbDiscount()).isEqualTo(observed.pricingEvidence().iherbDiscount());
	}

	@Test void discontinuedHasPriorityOverZeroPrice() throws Exception {
		var data = mapper.readTree("{\"id\":62771,\"url\":\"https://kr.iherb.com/pr/example/62771\",\"isDiscontinued\":true,\"isAvailableToPurchase\":false,\"listPriceAmount\":0,\"discountPriceAmount\":0}");
		assertThatThrownBy(() -> new ProductSourceObservationClient(mapper, null, null, null).parseIherb(data, "62771"))
			.hasMessageContaining("SOURCE_DISCONTINUED");
	}
	@Test void explicitZeroPriceIsNotOrdinaryOutOfStock() throws Exception {
		var data = mapper.readTree("{\"id\":72374,\"url\":\"https://kr.iherb.com/pr/example/72374\",\"isDiscontinued\":false,\"isAvailableToPurchase\":false,\"listPriceAmount\":0,\"discountPriceAmount\":0}");
		assertThatThrownBy(() -> new ProductSourceObservationClient(mapper, null, null, null).parseIherb(data, "72374"))
			.hasMessageContaining("SOURCE_PRICE_ZERO");
	}
	@Test void missingPricesAreNotConvertedToZero() throws Exception {
		var data = mapper.readTree("{\"id\":72374,\"url\":\"https://kr.iherb.com/pr/example/72374\",\"isAvailableToPurchase\":false}");
		assertThat(new ProductSourceObservationClient(mapper, null, null, null).parseIherb(data, "72374").goodsPriceKrw()).isNull();
	}

}
