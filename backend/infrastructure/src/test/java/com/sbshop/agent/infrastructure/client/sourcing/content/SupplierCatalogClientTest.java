package com.sbshop.agent.infrastructure.client.sourcing.content;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.product.content.ProductContentUrls;
import com.sbshop.agent.core.domain.product.enums.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SupplierCatalogClientTest {
	final ObjectMapper mapper = new ObjectMapper();
	final SupplierCatalogClient client = new SupplierCatalogClient(mapper);
	final String ftn = "https://www.fortnumandmason.com/fortnum-s-fig-fennel-chutney-250g";
	final String cok = "https://www.costco.co.uk/Grocery-Household/Tea-Coffee-Hot-Drinks/Tea-Coffee/Lavazza-Qualita-Rossa-Coffee-Beans-1kg/p/139465";

	JsonNode read(String name) throws Exception {
		return mapper.readTree(getClass().getResourceAsStream("/sourcing/" + name));
	}

	JsonNode fortnum() throws Exception {
		return read("ftn-content-2026-09-08-chutney.json");
	}

	JsonNode costco() throws Exception {
		return read("cok-content-2026-09-08-coffee.json");
	}

	@Test
	void actualFortnumSimpleProductPreservesWholeGalleryAndExplicitPriceStock() throws Exception {
		var document = fortnum();
		var p = document.path("data").path("products").path("items").get(0);
		var result = client.fortnum(document, ftn);
		assertThat(result.price()).isEqualByComparingTo("7.95");
		assertThat(result.currency()).isEqualTo("GBP");
		assertThat(result.status()).isEqualTo(StockStatus.IN_STOCK);
		assertThat(result.stock()).isNull();
		assertThat(result.images()).hasSize(p.path("media_gallery").size())
			.startsWith(p.path("image").path("url").asText());
		assertThat(result.html()).isEqualTo(p.path("description").path("html").asText());
	}

	@Test
	void fortnumPriceStockDoesNotFailWhenContentGalleryIsUnavailable() throws Exception {
		var document = fortnum();
		ObjectNode p = (ObjectNode)document.path("data").path("products").path("items").get(0);
		p.remove(List.of("description", "media_gallery", "image"));

		var result = client.fortnumPriceStock(document, ftn);

		assertThat(result.images()).isEmpty();
		assertThat(result.html()).isNull();
		assertThat(result.price()).isEqualByComparingTo("7.95");
		assertThat(result.status()).isEqualTo(StockStatus.IN_STOCK);
	}

	@Test
	void fortnumPriceStockAcceptsExplicitOutOfStockWithoutContent() throws Exception {
		var document = fortnum();
		ObjectNode p = (ObjectNode)document.path("data").path("products").path("items").get(0);
		p.put("stock_status", "OUT_OF_STOCK");
		p.remove(List.of("description", "media_gallery", "image"));

		assertThat(client.fortnumPriceStock(document, ftn).status()).isEqualTo(StockStatus.OUT_OF_STOCK);
	}

	@Test
	void actualCostcoChoosesOneFullImagePerGallerySlotAndDoesNotInventInventory() throws Exception {
		var doc = costco();
		var result = client.costco(doc, cok);
		assertThat(result.price()).isEqualByComparingTo("16.89");
		assertThat(result.status()).isEqualTo(StockStatus.OUT_OF_STOCK);
		assertThat(result.stock()).isNull();
		Set<Integer> indexes = new HashSet<>();
		doc.path("images").forEach(n -> indexes.add(n.path("galleryIndex").intValue()));
		assertThat(result.images()).hasSize(indexes.size())
			.allMatch(u -> u.startsWith("https://www.costco.co.uk/medias/sys_master/images/"));
	}

	@Test
	void fortnumWrongIdentityAndConfigurableProductAreRejected() throws Exception {
		var doc = fortnum();
		ObjectNode p = (ObjectNode)doc.path("data").path("products").path("items").get(0);
		p.put("url_key", "other");
		assertThatThrownBy(() -> client.fortnum(doc, ftn)).hasMessageContaining("SOURCE_IDENTITY");
		p.put("url_key", "fortnum-s-fig-fennel-chutney-250g").put("__typename", "ConfigurableProduct");
		assertThatThrownBy(() -> client.fortnum(doc, ftn)).hasMessageContaining("SOURCE_VARIANT");
	}

	@Test
	void fortnumUnknownStockAndUnequalPriceRangeAreNotGuessed() throws Exception {
		var doc = fortnum();
		ObjectNode p = (ObjectNode)doc.path("data").path("products").path("items").get(0);
		p.put("stock_status", "UNKNOWN");
		assertThatThrownBy(() -> client.fortnum(doc, ftn)).hasMessageContaining("SOURCE_STOCK");
		p.put("stock_status", "IN_STOCK");
		((ObjectNode)p.path("price_range").path("maximum_price").path("final_price")).put("value", 99);
		assertThatThrownBy(() -> client.fortnum(doc, ftn)).hasMessageContaining("SOURCE_VARIANT");
	}

	@Test
	void costcoNoFallbackSkuOrOutOfStockFromBlockedResponses() throws Exception {
		var doc = (ObjectNode)costco();
		doc.put("code", "139465_BD");
		assertThatThrownBy(() -> client.costco(doc, cok)).hasMessageContaining("SOURCE_IDENTITY");
		doc.put("code", "139465");
		((ObjectNode)doc.path("stock")).put("stockLevelStatus", "unknown");
		assertThatThrownBy(() -> client.costco(doc, cok)).hasMessageContaining("SOURCE_STOCK");
		assertThatThrownBy(() -> client.costco(mapper.createObjectNode().put("error", "Access denied"), cok))
			.hasMessageContaining("SOURCE_IDENTITY");
	}

	@Test
	void missingGalleryResolutionAndExternalImageHostAreRejected() throws Exception {
		var doc = (ObjectNode)costco();
		for (JsonNode image : doc.path("images"))
			if (image.path("galleryIndex").intValue() == 0 && image.path("format").asText().equals("superZoom"))
				((ObjectNode)image).put("format", "thumbnail");
		assertThatThrownBy(() -> client.costco(doc, cok)).hasMessageContaining("SOURCE_IMAGES");
		assertThatThrownBy(
			() -> ProductContentUrls.sourceImage(VendorType.COK, "https://evil.example/medias/sys_master/images/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProductContentUrls.sourceImage(VendorType.FTN,
			"https://www.fortnumandmason.com.evil.example/media/catalog/product/a.jpg"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unknownQueriesCredentialsAndNonProductSourcePathsAreRejected() {
		for (String url : List.of(ftn + "?other=1", ftn.replace("https://", "https://user@"),
			"https://www.fortnumandmason.com/graphql" + "?query=test"))
			assertThatThrownBy(() -> ProductContentUrls.source(VendorType.FTN, url))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
			() -> ProductContentUrls.source(VendorType.COK, "https://www.costco.co.uk/p/139465?variant=2"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
