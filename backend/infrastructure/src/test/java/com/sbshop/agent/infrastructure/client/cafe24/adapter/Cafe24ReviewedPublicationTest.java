package com.sbshop.agent.infrastructure.client.cafe24.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.cloudflare.config.R2Properties;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.*;

class Cafe24ReviewedPublicationTest {
	ObjectMapper mapper = new ObjectMapper();
	Cafe24RestClient rest = mock(Cafe24RestClient.class);
	Cafe24CategoryResolver categories = mock(Cafe24CategoryResolver.class);
	Product product = mock(Product.class);
	R2Properties r2 = new R2Properties();
	Function<URI, byte[]> images = mock(Function.class);
	Cafe24ReviewedPublication helper;
	byte[] png = Base64.getDecoder()
		.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2ioAAAAASUVORK5CYII=");
	ObjectNode current, variant, inventory;

	@BeforeEach
	void setup() {
		r2.setPublicUrl("https://cdn.example.com");
		helper = new Cafe24ReviewedPublication(rest, mapper, categories, r2, images);
		when(rest.accountReference()).thenReturn("mall:1");
		when(product.getSbCode()).thenReturn("SB-123");
		when(product.getProductName()).thenReturn("테스트 상품");
		when(product.getDetailHtml()).thenReturn("<p>상품 상세</p>");
		when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		when(product.getSalesQuantity()).thenReturn(300);
		when(product.getCostPrice()).thenReturn(new BigDecimal("10000.90"));
		when(product.getCategory()).thenReturn(ProductCategory.FOOD);
		when(product.getHostedImages()).thenReturn(List.of("https://cdn.example.com/a.png"));
		when(images.apply(any())).thenReturn(png);
		when(categories.resolve(any(), any(), any())).thenReturn(new MarketCategory("27", "식품", true));
		when(rest.get("/admin/categories/27?shop_no=1"))
			.thenReturn("{\"category\":{\"shop_no\":1,\"category_no\":27}}");
		when(rest.get("/admin/products/setting?shop_no=1"))
			.thenReturn("{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"P\"}}");
		when(rest.post(eq("/admin/products/images"), any()))
			.thenReturn("{\"image\":[{\"path\":\"https://mall.example.com/web/upload/a.png\"}]}");
		when(rest.put(any(), any())).thenReturn("{}");
		current = mapper.createObjectNode().put("shop_no", 1).put("product_no", 123).put("product_code", "P000000A")
			.put("custom_product_code", "SB-123").put("product_name", "테스트 상품").put("description", "<p>상품 상세</p>")
			.put("has_option", "F").put("shipping_fee_by_product", "F").put("price", "12300.00")
			.put("supply_price", "10000.00").put("market_sync", "F").put("selling", "F").put("display", "F")
			.put("detail_image", "https://mall.example.com/web/product/main.png");
		current.putArray("category").addObject().put("category_no", 27);
		variant = mapper.createObjectNode().put("shop_no", 1).put("variant_code", "P000000A000A").put("selling", "T");
		inventory = mapper.createObjectNode().put("shop_no", 1).put("variant_code", "P000000A000A").put("quantity", 0)
			.put("use_inventory", "F").put("display_soldout", "F");
		publish();
	}

	void publish(){when(rest.get("/admin/products/123?shop_no=1")).thenReturn(mapper.createObjectNode().set("product",current).toString());when(rest.get("/admin/products/123/variants?shop_no=1")).thenReturn(mapper.createObjectNode().set("variants",mapper.createArrayNode().add(variant)).toString());when(rest.get("/admin/products/123/variants/P000000A000A?shop_no=1")).thenReturn(mapper.createObjectNode().set("variant",variant).toString());when(rest.get("/admin/products/123/variants/P000000A000A/inventories?shop_no=1")).thenReturn(mapper.createObjectNode().set("inventory",inventory).toString());}

	String prepare() {
		return helper.prepare(product, new BigDecimal("12300")).payload();
	}

	@Test
	void preparationOnlyUploadsImagesAndFreezesOffStateWithoutUnsupportedInventoryFields() throws Exception {
		String frozen = prepare();
		JsonNode body = mapper.readTree(frozen).path("body"), p = body.path("request");
		assertThat(body.fieldNames()).toIterable().containsExactly("request");
		assertThat(p.path("selling").asText()).isEqualTo("F");
		assertThat(p.path("display").asText()).isEqualTo("F");
		assertThat(p.has("supply_quantity") || p.has("quantity") || p.has("variants") || p.has("use_external_image"))
			.isFalse();
		assertThat(mapper.readTree(frozen).path("quantity").asInt()).isEqualTo(300);
		assertThat(p.path("supply_price").asInt()).isEqualTo(10000);
		verify(rest).post("/admin/products/images",
			Map.of("requests", List.of(Map.of("image", Base64.getEncoder().encodeToString(png)))));
		verify(rest, never()).post(eq("/admin/products"), any());
		verify(rest, never()).put(any(), any());
	}

	@Test
	void creationCallbackImmediatelyPrecedesOnlyProductPostAndValidatesReceipt() {
		String frozen = prepare();
		when(rest.post(eq("/admin/products"), any()))
			.thenReturn(mapper.createObjectNode().set("product", current).toString());
		Runnable guard = mock(Runnable.class);
		assertThat(helper.submit(product, UUID.randomUUID().toString(), frozen, guard)).containsEntry("product_no",
			"123");
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).post(eq("/admin/products"), any());
		verify(rest, never()).put(any(), any());
	}

	@Test
	void callbackFailureOrChangedAccountPreventsPost() {
		String frozen = prepare();
		RuntimeException stop = new RuntimeException("stop");
		assertThatThrownBy(() -> helper.submit(product, UUID.randomUUID().toString(), frozen, () -> {
			throw stop;
		})).isSameAs(stop);
		assertThatThrownBy(() -> helper.submit(product, UUID.randomUUID().toString(), frozen,
			() -> when(rest.accountReference()).thenReturn("another"))).hasMessageContaining("계정");
		verify(rest, never()).post(eq("/admin/products"), any());
	}

	@Test
	void readIsSideEffectFreeAndSetupDoesOneInventoryPutThenOneActivationPutAfterAnotherRead() {
		String frozen = prepare();
		assertThat(helper.readPublication("123", "SB-123", frozen).setupRequired()).isTrue();
		verify(rest, never()).put(any(), any());
		Runnable guard = mock(Runnable.class);
		helper.finalizePublication("123", "SB-123", frozen, guard);
		verify(rest).put("/admin/products/123/variants/P000000A000A/inventories",
			Map.of("shop_no", 1, "request", Map.of("quantity", 300, "use_inventory", "T", "display_soldout", "T")));
		verify(rest, never()).put(eq("/admin/products/123"), any());
		inventory.put("quantity", 300).put("use_inventory", "T").put("display_soldout", "T");
		publish();
		helper.finalizePublication("123", "SB-123", frozen, guard);
		verify(rest).put("/admin/products/123",
			Map.of("shop_no", 1, "request", Map.of("selling", "T", "display", "T")));
		verify(guard, times(2)).run();
		assertThat(helper.readPublication("123", "SB-123", frozen).verified()).isFalse();
		current.put("selling", "T").put("display", "T");
		publish();
		assertThat(helper.readPublication("123", "SB-123", frozen).verified()).isTrue();
	}

	@Test
	void wrongSbShopOrMarketPlusNeverAllowsSetup() {
		String frozen = prepare();
		assertThatThrownBy(() -> helper.finalizePublication("123", "another", frozen, () -> {}))
			.hasMessageContaining("SB코드");
		current.put("shop_no", 2);
		publish();
		assertThatThrownBy(() -> helper.finalizePublication("123", "SB-123", frozen, () -> {}))
			.hasMessageContaining("쇼핑몰");
		current.put("shop_no", 1).put("market_sync", "T");
		publish();
		assertThatThrownBy(() -> helper.finalizePublication("123", "SB-123", frozen, () -> {}))
			.hasMessageContaining("마켓플러스");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void fieldMismatchOrDifferentImageDoesNotStartSelling() {
		String frozen = prepare();
		current.put("product_name", "different");
		publish();
		assertThat(helper.readPublication("123", "SB-123", frozen).setupRequired()).isFalse();
		assertThatThrownBy(() -> helper.finalizePublication("123", "SB-123", frozen, () -> {}))
			.hasMessageContaining("product_name");
		current.put("product_name", "테스트 상품");
		publish();
		byte[] different = png.clone();
		different[20]++;
		when(images.apply(URI.create("https://mall.example.com/web/product/main.png"))).thenReturn(different);
		assertThat(helper.readPublication("123", "SB-123", frozen).detail()).contains("이미지 원본");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void variantSuspensionIsNotResumed() {
		String frozen = prepare();
		variant.put("selling", "F");
		publish();
		assertThat(helper.readPublication("123", "SB-123", frozen).setupRequired()).isFalse();
		assertThatThrownBy(() -> helper.finalizePublication("123", "SB-123", frozen, () -> {}))
			.hasMessageContaining("정지");
		verify(rest, never()).put(any(), any());
	}

	@Test void sourceUnknownOrUntrustedImageOrUnsafeHtmlBlocksBeforeAnyUpload(){when(product.getStockStatus()).thenReturn(null);assertThatThrownBy(this::prepare).hasMessageContaining("관측");when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);when(product.getHostedImages()).thenReturn(List.of("https://evil.example.com/a.png"));assertThatThrownBy(this::prepare).hasMessageContaining("호스팅");when(product.getHostedImages()).thenReturn(List.of("https://cdn.example.com/a.png"));when(product.getDetailHtml()).thenReturn("<img src=x onerror=alert(1)>");assertThatThrownBy(this::prepare).hasMessageContaining("실행");verify(rest,never()).post(any(),any());}

	@Test void missingBrandOrUnprovenCategoryAndTaxBasisDoNotUseFallbacks(){when(product.getBrand()).thenReturn("Brand");when(rest.get("/admin/brands?shop_no=1&brand_name=Brand")).thenReturn("{\"brands\":[]}");assertThatThrownBy(this::prepare).hasMessageContaining("브랜드");when(product.getBrand()).thenReturn(null);when(rest.get("/admin/products/setting?shop_no=1")).thenReturn("{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"B\"}}");assertThatThrownBy(this::prepare).hasMessageContaining("계산 기준");verify(rest,never()).post(any(),any());}

	@Test void malformedImageCountAndInvalidProductReceiptStayUnconfirmed(){when(rest.post(eq("/admin/products/images"),any())).thenReturn("{\"image\":[]}");assertThatThrownBy(this::prepare).hasMessageContaining("결과 수");when(rest.post(eq("/admin/products/images"),any())).thenReturn("{\"image\":[{\"path\":\"https://mall.example.com/web/a.png\"}]}");String frozen=prepare();when(rest.post(eq("/admin/products"),any())).thenReturn("{\"product\":{\"shop_no\":2,\"product_no\":123,\"product_code\":\"P000000A\",\"custom_product_code\":\"SB-123\"}}");assertThatThrownBy(()->helper.submit(product,UUID.randomUUID().toString(),frozen,()->{})).hasMessageContaining("생성 응답");}

	@Test
	void http429PreservesRetryAfterAndBusinessRejectionIsTyped() {
		var headers = new HttpHeaders();
		headers.set("Retry-After", "120");
		when(rest.get("/admin/products/setting?shop_no=1")).thenThrow(
			HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "limited", headers, new byte[0], null));
		assertThatThrownBy(this::prepare)
			.isInstanceOfSatisfying(com.sbshop.agent.core.domain.market.sync.MarketTransferFailure.class, e -> {
				assertThat(e.rateLimited()).isTrue();
				assertThat(e.getRetryAfter()).isNotNull();
			});
		doReturn("{\"error\":{\"message\":\"rejected\"}}").when(rest).get("/admin/products/setting?shop_no=1");
		assertThatThrownBy(this::prepare).isInstanceOfSatisfying(
			com.sbshop.agent.core.domain.market.sync.MarketTransferFailure.class,
			e -> assertThat(e.getCode()).isEqualTo("HTTP_400"));
	}
}
