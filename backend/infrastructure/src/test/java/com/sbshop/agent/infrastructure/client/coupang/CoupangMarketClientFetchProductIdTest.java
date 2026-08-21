package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangMarketClientFetchProductIdTest {

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

	private static final String BASE_PATH = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";

	private final ObjectMapper real = new ObjectMapper();

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService);
	}

	private void delegateReadTree(String json) throws Exception {
		JsonNode root = real.readTree(json);
		when(objectMapper.readTree(json)).thenReturn(root);
	}

	@Test
	@DisplayName("data.productId 존재 → Optional.of(문자열 productId) 반환")
	void fetchProductId_present_returnsStringId() throws Exception {
		String json = "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":11658784734,"
			+ "\"productId\":9334584158,\"items\":[]}}";
		when(restClient.get(eq(BASE_PATH + "/11658784734"))).thenReturn(json);
		delegateReadTree(json);

		Optional<String> result = client.fetchProductId("11658784734");

		assertThat(result).contains("9334584158");
	}

	@Test
	@DisplayName("data.productId 부재 → Optional.empty()")
	void fetchProductId_missing_returnsEmpty() throws Exception {
		String json = "{\"data\":{\"sellerProductId\":1}}";
		when(restClient.get(eq(BASE_PATH + "/1"))).thenReturn(json);
		delegateReadTree(json);

		Optional<String> result = client.fetchProductId("1");

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("blank 입력 → restClient 호출 없이 Optional.empty()")
	void fetchProductId_blankInput_returnsEmptyWithoutCall() {
		Optional<String> result = client.fetchProductId("   ");

		assertThat(result).isEmpty();
		verify(restClient, never()).get(anyString());
	}

	@Test
	@DisplayName("null 입력 → restClient 호출 없이 Optional.empty()")
	void fetchProductId_nullInput_returnsEmptyWithoutCall() {
		Optional<String> result = client.fetchProductId(null);

		assertThat(result).isEmpty();
		verify(restClient, never()).get(anyString());
	}

	@Test
    @DisplayName("restClient 예외 → 예외 전파 없이 Optional.empty()")
    void fetchProductId_restClientThrows_returnsEmpty() {
        when(restClient.get(any())).thenThrow(new RuntimeException("boom"));

        Optional<String> result = client.fetchProductId("305");

        assertThat(result).isEmpty();
    }
}
