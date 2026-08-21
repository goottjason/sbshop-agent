package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
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
	private static final String SUCCESS_ENVELOPE = "{\"code\":\"SUCCESS\",\"message\":\"OK\"}";

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService, new CoupangAttributeValueResolver());
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
		verify(restClient).put(eq(BASE + "/quantities/1"), any());
		verify(restClient).put(eq(BASE + "/sales/stop"), any());
	}

	@Test
	@DisplayName("D-181: prices PUT 이 200 + code=ERROR 봉투면 예외")
	void priceErrorEnvelopeThrows() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE + "/prices/30000"), any()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"가격 변경에 실패했습니다\"}");

		assertThatThrownBy(() -> client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 30000, 999, false))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("가격 변경에 실패했습니다");
		verify(restClient, never()).put(eq(BASE + "/quantities/999"), any());
	}

	@Test
	@DisplayName("D-181: quantities PUT 이 code=ERROR 봉투면 예외 — 판매상태 PUT 미호출")
	void quantityErrorEnvelopeThrows() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE + "/quantities/1"), any()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"재고 변경에 실패했습니다\"}");

		assertThatThrownBy(() -> client.syncPriceAndStock(ITEM_ID, new HashMap<>(), null, 1, true))
			.isInstanceOf(RuntimeException.class);
		verify(restClient, never()).put(eq(BASE + "/sales/stop"), any());
	}

	@Test
	@DisplayName("D-181: sales PUT 이 code=ERROR 봉투면 예외")
	void salesErrorEnvelopeThrows() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE + "/quantities/999"), any())).thenReturn(SUCCESS_ENVELOPE);
		when(restClient.put(eq(BASE + "/sales/resume"), any()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"판매재개에 실패했습니다\"}");

		assertThatThrownBy(() -> client.syncPriceAndStock(ITEM_ID, new HashMap<>(), null, 999, false))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("판매재개에 실패했습니다");
	}

	@Test
	@DisplayName("D-181: 응답 본문이 빈 문자열·봉투 아님·code 부재면 기존대로 통과")
	void nonEnvelopeResponsesPass() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE + "/prices/30000"), any())).thenReturn("");
		when(restClient.put(eq(BASE + "/quantities/999"), any())).thenReturn("OK");
		when(restClient.put(eq(BASE + "/sales/resume"), any())).thenReturn("{\"message\":\"OK\"}");

		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 30000, 999, false);

		verify(restClient).put(eq(BASE + "/sales/resume"), any());
	}

	@Test
	@DisplayName("D-181: 성공 봉투(code=SUCCESS)면 통과")
	void successEnvelopePasses() throws Exception {
		stubJsonParsing();
		when(restClient.put(anyString(), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncPriceAndStock(ITEM_ID, new HashMap<>(), 30000, 999, false);

		verify(restClient).put(eq(BASE + "/sales/resume"), any());
	}

	private void stubJsonParsing() throws Exception {
		ObjectMapper real = new ObjectMapper();
		lenient().when(objectMapper.readTree(anyString()))
			.thenAnswer(invocation -> real.readTree((String)invocation.getArgument(0)));
	}
}
