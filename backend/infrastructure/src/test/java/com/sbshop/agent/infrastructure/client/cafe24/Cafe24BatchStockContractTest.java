package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Cafe24BatchStockContractTest {
	private final Cafe24RestClient rest = mock(Cafe24RestClient.class);
	private final Cafe24MarketClient client = new Cafe24MarketClient(new ObjectMapper(), rest, null, null, null, null);
	private final String path = "/admin/products/123";
	private final String variant = "P0000001000A";

	private void fixture(String selling, String variantSelling) {
		fixture(selling, variantSelling, "T");
	}

	private void fixture(String selling, String variantSelling, String hasOption) {
		when(rest.accountReference()).thenReturn("account-A");
		when(rest.get(path + "?shop_no=1")).thenReturn("""
			{"product":{"shop_no":1,"product_no":123,"product_code":"P0000001",
			"custom_product_code":"SB123","selling":"%s","market_sync":"T","has_option":"%s","tax_calculation":"A","price":"12500"}}
			""".formatted(selling, hasOption));
		when(rest.get(path + "/variants?shop_no=1")).thenReturn("""
			{"variants":[{"shop_no":1,"variant_code":"P0000001000A"}]}
			""");
		when(rest.get(path + "/variants/" + variant + "?shop_no=1")).thenReturn("""
			{"variant":{"shop_no":1,"variant_code":"P0000001000A","selling":"%s"}}
			""".formatted(variantSelling));
		when(rest.get(path + "/variants/" + variant + "/inventories?shop_no=1")).thenReturn("""
			{"inventory":{"shop_no":1,"variant_code":"P0000001000A","quantity":300,"use_inventory":"F","display_soldout":"F"}}
			""");
	}

	@Test
	void connectedProductCanUpdatePriceWhileNotSelling() {
		fixture("F", "F");
		assertThat(client.readSalePrice("123", null).writable()).isTrue();
	}

	@Test
	void stockReadPreservesBothSellingStatesAndDoesNotBlockMarketPlusOrUnusedInventory() {
		fixture("F", "T");
		var read = client.readStockQuantity("123", null, "SB123");
		assertThat(read.writable()).isTrue();
		assertThat(read.saleState()).isEqualTo("F");
		assertThat(read.stockState()).isEqualTo("T");
	}

	@Test
	void stopSellingBeforeWritingZeroInventory() {
		fixture("T", "T");
		Runnable guard = mock(Runnable.class);
		client.writeStockQuantity("123", variant, "SB123", 0, "account-A", guard);
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).put(path, Map.of("shop_no", 1, "request", Map.of("selling", "F")));
		order.verify(rest).put(path + "/variants/" + variant + "/inventories",
			Map.of("shop_no", 1, "request", Map.of("quantity", 0)));
		verify(rest, times(2)).put(any(), any());
	}

	@Test
	void replenishInventoryBeforeResumingVariantAndProduct() {
		fixture("F", "F");
		client.writeStockQuantity("123", variant, "SB123", 300, "account-A", () -> {});
		var order = inOrder(rest);
		order.verify(rest).put(path + "/variants/" + variant + "/inventories",
			Map.of("shop_no", 1, "request", Map.of("quantity", 300)));
		order.verify(rest).put(path + "/variants/" + variant, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
		order.verify(rest).put(path, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
	}

	@Test
	void failedInventoryWriteMustNotResumeSelling() {
		fixture("F", "F");
		when(rest.put(contains("inventories"), any())).thenThrow(new IllegalStateException("failed inventory"));
		assertThatThrownBy(() -> client.writeStockQuantity("123", variant, "SB123", 300, "account-A", () -> {}))
			.isInstanceOf(RuntimeException.class);
		verify(rest, never()).put(eq(path), any());
		verify(rest, never()).put(eq(path + "/variants/" + variant), any());
	}

	@Test
	void mismatchedSbCodeCannotWrite() {
		fixture("T", "T");
		assertThatThrownBy(() -> client.writeStockQuantity("123", variant, "WRONG", 0, "account-A", () -> {}))
			.isInstanceOf(RuntimeException.class);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void simpleProductResumesThroughProductEndpointWithoutRejectedVariantWrite() {
		fixture("F", "F", "F");
		client.writeStockQuantity("123", variant, "SB123", 300, "account-A", () -> {});
		var order = inOrder(rest);
		order.verify(rest).put(path + "/variants/" + variant + "/inventories",
			Map.of("shop_no", 1, "request", Map.of("quantity", 300)));
		order.verify(rest).put(path, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
		verify(rest, never()).put(eq(path + "/variants/" + variant), any());
	}

}
