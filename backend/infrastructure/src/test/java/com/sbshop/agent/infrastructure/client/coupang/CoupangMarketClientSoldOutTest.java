package com.sbshop.agent.infrastructure.client.coupang;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-B Task 2: CoupangMarketClient soldOut → quantities/{qty} + sales/stop|resume 특성화 테스트.
 */
@ExtendWith(MockitoExtension.class)
class CoupangMarketClientSoldOutTest {

	@Mock
	private CoupangProperties properties;
	@Mock
	private ObjectMapper objectMapper;
	@Mock
	private CoupangRestClient restClient;
	@Mock
	private CoupangCategoryPredictor categoryPredictor;
	@Mock
	private CoupangProductParser productParser;
	@Mock
	private CoupangSearchTagGenerator searchTagGenerator;
	@Mock
	private CoupangDataMapper dataMapper;
	@Mock
	private CoupangMetaService metaService;

	private CoupangMarketClient client;

	private static final String ITEM_ID = "V001";
	private static final String BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + ITEM_ID;

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService);
	}

	@Test
	@DisplayName("soldOut=true → quantities/1 PUT + sales/stop PUT")
	void soldOutCallsQuantityOneAndSalesStop() {
		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), null, 1, true);

		verify(restClient).put(eq(BASE + "/quantities/1"), any());
		verify(restClient).put(eq(BASE + "/sales/stop"), any());
		verify(restClient, never()).put(eq(BASE + "/sales/resume"), any());
	}

	@Test
	@DisplayName("soldOut=false → quantities/999 PUT + sales/resume PUT")
	void inStockCallsQuantity999AndSalesResume() {
		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), null, 999, false);

		verify(restClient).put(eq(BASE + "/quantities/999"), any());
		verify(restClient).put(eq(BASE + "/sales/resume"), any());
		verify(restClient, never()).put(eq(BASE + "/sales/stop"), any());
	}

	@Test
	@DisplayName("price!=null → prices/{price} PUT 호출")
	void withPriceCallsPriceEndpoint() {
		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 30000, 999, false);

		verify(restClient).put(eq(BASE + "/prices/30000"), any());
	}

	@Test
	@DisplayName("price==null → prices PUT 미호출")
	void withNullPriceSkipsPriceEndpoint() {
		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), null, 1, true);

		verify(restClient, never()).put(org.mockito.ArgumentMatchers.contains("/prices/"), any());
		// quantities + sales/stop은 항상 호출됨
		verify(restClient).put(eq(BASE + "/quantities/1"), any());
		verify(restClient).put(eq(BASE + "/sales/stop"), any());
	}
}
