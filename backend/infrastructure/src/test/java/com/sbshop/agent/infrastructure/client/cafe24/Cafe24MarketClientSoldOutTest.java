package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24MarketClientSoldOutTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private HtmlImageExtractor imageExtractor;
	@Mock
	private Cafe24CategoryResolver categoryResolver;

	private Cafe24MarketClient client;

	private static final String ITEM_ID = "C100";

	@BeforeEach
	void setUp() {
		client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor, categoryResolver, null);
	}

	@Test
	@DisplayName("soldOut=true → request.supply_quantity==\"1\", request.selling==\"F\"")
	void soldOutSetsSupplyQuantityOneAndSellingF() {
		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), null, 1, true);

		verify(cafe24RestClient).put(eq("/admin/products/" + ITEM_ID), captor.capture());
		@SuppressWarnings("unchecked") Map<String, Object> request = (Map<String, Object>)captor.getValue()
			.get("request");
		assertThat(request.get("supply_quantity")).isEqualTo("1");
		assertThat(request.get("selling")).isEqualTo("F");
	}

	@Test
	@DisplayName("soldOut=false → request.supply_quantity==\"999\", request.selling==\"T\"")
	void inStockSetsSupplyQuantity999AndSellingT() {
		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), null, 999, false);

		verify(cafe24RestClient).put(eq("/admin/products/" + ITEM_ID), captor.capture());
		@SuppressWarnings("unchecked") Map<String, Object> request = (Map<String, Object>)captor.getValue()
			.get("request");
		assertThat(request.get("supply_quantity")).isEqualTo("999");
		assertThat(request.get("selling")).isEqualTo("T");
	}

	@Test
	@DisplayName("price != null → request.price==\"30000.00\", supply_quantity==\"999\", selling==\"T\"")
	void withPriceSetsRequestBodyPrice() {
		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 30000, 999, false);

		verify(cafe24RestClient).put(eq("/admin/products/" + ITEM_ID), captor.capture());
		@SuppressWarnings("unchecked") Map<String, Object> request = (Map<String, Object>)captor.getValue()
			.get("request");
		assertThat(request.get("price")).isEqualTo("30000.00");
		assertThat(request.get("supply_quantity")).isEqualTo("999");
		assertThat(request.get("selling")).isEqualTo("T");
	}
}
