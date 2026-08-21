package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangMarketClientDeleteTest {

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

	private static final String SELLER_PRODUCT_ID = "1234567";
	private static final String DELETE_PATH = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/"
		+ SELLER_PRODUCT_ID;

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService);
	}

	@Test
	@DisplayName("정상: seller-products/{sellerProductId} DELETE 경로 호출")
	void deleteCallsSellerProductsDeletePath() {
		client.deleteFromMarket(SELLER_PRODUCT_ID);

		verify(restClient).requestWithBody(eq("DELETE"), eq(DELETE_PATH), isNull());
	}

	@Test
	@DisplayName("REST 오류(주문이력 하드삭제 거부 등) 시 예외 전파")
	void deletePropagatesRestError() {
		when(restClient.requestWithBody(eq("DELETE"), eq(DELETE_PATH), isNull()))
			.thenThrow(new RuntimeException("Coupang API 호출 실패"));

		assertThatThrownBy(() -> client.deleteFromMarket(SELLER_PRODUCT_ID))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("marketItemId 공백이면 삭제 호출 없이 예외")
	void deleteRejectsBlankId() {
		assertThatThrownBy(() -> client.deleteFromMarket("  "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("D-181: DELETE 응답이 200 + code=ERROR 봉투면 예외")
	void deleteErrorEnvelopeThrows() throws Exception {
		stubJsonParsing();
		when(restClient.requestWithBody(eq("DELETE"), eq(DELETE_PATH), isNull()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"삭제에 실패했습니다\"}");

		assertThatThrownBy(() -> client.deleteFromMarket(SELLER_PRODUCT_ID))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("삭제에 실패했습니다");
	}

	@Test
	@DisplayName("D-181: DELETE 성공 봉투면 통과")
	void deleteSuccessEnvelopePasses() throws Exception {
		stubJsonParsing();
		when(restClient.requestWithBody(eq("DELETE"), eq(DELETE_PATH), isNull()))
			.thenReturn("{\"code\":\"SUCCESS\",\"message\":\"OK\"}");

		client.deleteFromMarket(SELLER_PRODUCT_ID);

		verify(restClient).requestWithBody(eq("DELETE"), eq(DELETE_PATH), isNull());
	}

	@Test
	@DisplayName("D-181: DELETE 응답 본문이 없거나 봉투가 아니면 기존대로 통과")
	void deleteNonEnvelopeResponsePasses() throws Exception {
		stubJsonParsing();
		when(restClient.requestWithBody(eq("DELETE"), eq(DELETE_PATH), isNull())).thenReturn("");

		client.deleteFromMarket(SELLER_PRODUCT_ID);

		verify(restClient).requestWithBody(eq("DELETE"), eq(DELETE_PATH), isNull());
	}

	private void stubJsonParsing() throws Exception {
		ObjectMapper real = new ObjectMapper();
		lenient().when(objectMapper.readTree(anyString()))
			.thenAnswer(invocation -> real.readTree((String)invocation.getArgument(0)));
	}
}
