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
		fixture(selling, variantSelling, hasOption, 300);
	}

	private void fixture(String selling, String variantSelling, String hasOption, int quantity) {
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
		when(rest.get(path + "/variants/" + variant + "/inventories?shop_no=1")).thenReturn(inventories("T", quantity));
	}

	private String inventories(String useInventory) {
		return inventories(useInventory, 300);
	}

	private String inventories(String useInventory, int quantity) {
		return """
			{"inventory":{"shop_no":1,"variant_code":"P0000001000A","quantity":%d,"use_inventory":"%s","display_soldout":"F"}}
			""".formatted(quantity, useInventory);
	}

	@Test
	void priceIsNotSentWhileTheConnectedProductIsNotSelling() {
		fixture("F", "F");
		var read = client.readSalePrice("123", null);
		assertThat(read.writable()).isFalse();
		assertThat(read.reason()).contains("판매안함");
	}

	@Test
	void priceStaysWritableWhileTheConnectedProductIsSelling() {
		fixture("T", "T");
		assertThat(client.readSalePrice("123", null).writable()).isTrue();
	}

	@Test
	void stockReadPreservesBothSellingStatesAndDoesNotBlockMarketPlusOrUnusedInventory() {
		fixture("F", "T");
		when(rest.get(path + "/variants/" + variant + "/inventories?shop_no=1")).thenReturn(inventories("F"));
		var read = client.readStockQuantity("123", null, "SB123");
		assertThat(read.writable()).isTrue();
		assertThat(read.saleState()).isEqualTo("F");
		assertThat(read.stockState()).isEqualTo("T");
	}

	@Test
	void soldOutStopsSellingAndNeverSendsInventoryQuantity() {
		fixture("T", "T");
		Runnable guard = mock(Runnable.class);
		client.writeStockQuantity("123", variant, "SB123", 0, "account-A", guard);
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).put(path, Map.of("shop_no", 1, "request", Map.of("selling", "F")));
		verify(rest, times(1)).put(any(), any());
		verify(rest, never()).put(contains("inventories"), any());
	}

	@Test
	void soldOutProductThatIsAlreadyStoppedSendsNothingAtAll() {
		fixture("F", "T");
		Runnable guard = mock(Runnable.class);
		client.writeStockQuantity("123", variant, "SB123", 0, "account-A", guard);
		verify(guard, never()).run();
		verify(rest, never()).put(any(), any());
	}

	@Test
	void restockResumesSellingBeforeRestoringQuantity() {
		fixture("F", "F", "T", 0);
		client.writeStockQuantity("123", variant, "SB123", 300, "account-A", () -> {});
		var order = inOrder(rest);
		order.verify(rest).put(path + "/variants/" + variant, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
		order.verify(rest).put(path, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
		order.verify(rest).put(path + "/variants/" + variant + "/inventories",
			Map.of("shop_no", 1, "request", Map.of("quantity", 300)));
		verify(rest, times(3)).put(any(), any());
	}

	@Test
	void restockKeepsTheMarketQuantityWhenItIsAlreadyPositive() {
		fixture("F", "F", "T", 120);
		client.writeStockQuantity("123", variant, "SB123", 300, "account-A", () -> {});
		verify(rest).put(path + "/variants/" + variant, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
		verify(rest).put(path, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
		verify(rest, times(2)).put(any(), any());
		verify(rest, never()).put(contains("inventories"), any());
	}

	@Test
	void stoppedVariantIsStillCorrectedThroughTheProductEndpointWhileTheProductSells() {
		fixture("T", "F", "F", 120);
		client.writeStockQuantity("123", variant, "SB123", 300, "account-A", () -> {});
		verify(rest).put(path, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
		verify(rest, times(1)).put(any(), any());
	}

	@Test
	void sellingProductWithStockOnHandSendsNothingAtAll() {
		fixture("T", "T", "T", 120);
		Runnable guard = mock(Runnable.class);
		client.writeStockQuantity("123", variant, "SB123", 300, "account-A", guard);
		verify(guard, never()).run();
		verify(rest, never()).put(any(), any());
	}

	@Test
	void failedResumeMustNotSendQuantity() {
		fixture("F", "F", "T", 0);
		when(rest.put(eq(path), any())).thenThrow(new IllegalStateException("failed selling"));
		assertThatThrownBy(() -> client.writeStockQuantity("123", variant, "SB123", 300, "account-A", () -> {}))
			.isInstanceOf(RuntimeException.class);
		verify(rest, never()).put(contains("inventories"), any());
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
		fixture("F", "F", "F", 0);
		client.writeStockQuantity("123", variant, "SB123", 300, "account-A", () -> {});
		var order = inOrder(rest);
		order.verify(rest).put(path, Map.of("shop_no", 1, "request", Map.of("selling", "T")));
		order.verify(rest).put(path + "/variants/" + variant + "/inventories",
			Map.of("shop_no", 1, "request", Map.of("quantity", 300)));
		verify(rest, times(2)).put(any(), any());
		verify(rest, never()).put(eq(path + "/variants/" + variant), any());
	}

}
