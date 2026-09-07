package com.sbshop.agent.infrastructure.client.cafe24;

import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;

final class Cafe24VariantFixture {
	static final String ID = "7034";
	static final String CODE = "P0000KKO000A";
	static final String PRODUCT = "/admin/products/7034";
	static final String VARIANT = PRODUCT + "/variants/" + CODE;
	static final String INVENTORY = VARIANT + "/inventories";
	final Cafe24RestClient rest = mock(Cafe24RestClient.class);
	final Cafe24MarketClient client = new Cafe24MarketClient(new ObjectMapper(), rest, null, null, null, null);

	Cafe24VariantFixture() {
        when(rest.accountReference()).thenReturn("account-A");
        when(rest.get(PRODUCT + "?shop_no=1")).thenReturn(product("T", "F", "A", "T", 23800));
        when(rest.get(PRODUCT + "/variants?shop_no=1"))
            .thenReturn("{\"variants\":[{\"shop_no\":1,\"variant_code\":\"" + CODE + "\"}]}");
        when(rest.get(VARIANT + "?shop_no=1")).thenReturn(variant("00012345678905"));
        when(rest.get(INVENTORY + "?shop_no=1")).thenReturn(inventory(500, "T", "T"));
    }

	static String product(String selling, String market, String tax, String hasOption, int price) {
		return "{\"product\":{\"shop_no\":1,\"product_no\":7034,\"product_code\":\"P0000KKO\","
			+ "\"custom_product_code\":\"200630WA013\",\"selling\":\"" + selling + "\",\"market_sync\":\"" + market
			+ "\",\"tax_calculation\":\"" + tax + "\",\"has_option\":\"" + hasOption + "\",\"price\":\"" + price
			+ ".00\"}}";
	}

	static String variant(String gtin) {
		return "{\"variant\":{\"shop_no\":1,\"variant_code\":\"" + CODE
			+ "\",\"gtin\":\"" + gtin + "\",\"selling\":\"T\"}}";
	}

	static String inventory(int quantity, String use, String display) {
		return "{\"inventory\":{\"shop_no\":1,\"variant_code\":\"" + CODE + "\",\"quantity\":" + quantity
			+ ",\"use_inventory\":\"" + use + "\",\"display_soldout\":\"" + display
			+ "\",\"inventory_control_type\":\"A\"}}";
	}
}
