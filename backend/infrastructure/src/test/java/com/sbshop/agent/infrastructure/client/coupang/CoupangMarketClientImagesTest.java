package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangMarketClientImagesTest {

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
	private static final String SUCCESS_ENVELOPE = "{\"code\":\"SUCCESS\",\"message\":\"OK\"}";

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService);
	}

	@Test
	@DisplayName("D-092: items 존재 → id 없는 base로 PUT(requested=true), /approvals 미호출")
	void syncImagesAndHtml_withItems_putsWithRequestedTrue() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		Map<String, Object> firstItem = new HashMap<>();
		Map<String, Object> raw = new HashMap<>();
		raw.put("items", List.of(firstItem));

		client.syncImagesAndHtml(null, "305", raw, List.of("u0", "u1"), "<html>");

		verify(restClient).put(eq(BASE_PATH), any());
		verify(restClient, never()).put(eq(BASE_PATH + "/305/approvals"), any());
	}

	@Test
	@DisplayName("D-092: PUT 바디에 requested=true 를 넣어 자동 승인요청(임시저장 방지)")
	void syncImagesAndHtml_setsRequestedTrue() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		Map<String, Object> firstItem = new HashMap<>();
		Map<String, Object> raw = new HashMap<>();
		raw.put("items", List.of(firstItem));

		client.syncImagesAndHtml(null, "305", raw, List.of("u0"), "<html>");

		assertThat(raw.get("requested")).isEqualTo(true);
	}

	@Test
	@DisplayName("rawData={}(items 없음) → GET 으로 전체 페이로드 조회 후 PUT + 승인요청")
	void syncImagesAndHtml_noItems_getsFullPayloadThenPutAndApproval() throws Exception {
		String responseJson = "{\"code\":200,\"message\":\"OK\",\"data\":{\"items\":[{}]}}";
		when(restClient.get(eq(BASE_PATH + "/305"))).thenReturn(responseJson);

		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		ObjectMapper real = new ObjectMapper();
		JsonNode root = real.readTree(responseJson);
		Map<String, Object> data = real.convertValue(root.path("data"),
			new TypeReference<Map<String, Object>>() {});
		when(objectMapper.convertValue(eq(root.path("data")), any(TypeReference.class))).thenReturn(data);

		client.syncImagesAndHtml(null, "305", new HashMap<>(), List.of("u0"), "<html>");

		InOrder order = inOrder(restClient);
		order.verify(restClient).get(eq(BASE_PATH + "/305"));
		order.verify(restClient).put(eq(BASE_PATH), any());
		verify(restClient, never()).put(eq(BASE_PATH + "/305/approvals"), any());
	}

	@Test
	@DisplayName("rawData=null(items 없음) → GET 으로 조회 후 PUT + 승인요청")
	void syncImagesAndHtml_nullRawData_getsFullPayload() throws Exception {
		String responseJson = "{\"code\":200,\"message\":\"OK\",\"data\":{\"items\":[{}]}}";
		when(restClient.get(eq(BASE_PATH + "/305"))).thenReturn(responseJson);

		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		ObjectMapper real = new ObjectMapper();
		JsonNode root = real.readTree(responseJson);
		Map<String, Object> data = real.convertValue(root.path("data"),
			new TypeReference<Map<String, Object>>() {});
		when(objectMapper.convertValue(eq(root.path("data")), any(TypeReference.class))).thenReturn(data);

		client.syncImagesAndHtml(null, "305", null, List.of("u0"), "<html>");

		verify(restClient).get(eq(BASE_PATH + "/305"));
		verify(restClient).put(eq(BASE_PATH), any());
		verify(restClient, never()).put(eq(BASE_PATH + "/305/approvals"), any());
	}

	@Test
	@DisplayName("GET 응답에 data 가 비어 있으면 IllegalStateException")
	void syncImagesAndHtml_emptyData_throwsIllegalStateException() throws Exception {
		String responseJson = "{\"code\":200,\"message\":\"OK\",\"data\":{}}";
		when(restClient.get(eq(BASE_PATH + "/305"))).thenReturn(responseJson);

		stubJsonParsing();
		ObjectMapper real = new ObjectMapper();
		JsonNode root = real.readTree(responseJson);
		when(objectMapper.convertValue(eq(root.path("data")), any(TypeReference.class)))
			.thenReturn(new HashMap<>());

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", new HashMap<>(), List.of("u0"), "<html>"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("marketItemId 부재 → IllegalStateException 전파")
	void syncImagesAndHtml_missingMarketItemId_throwsIllegalStateException() {
		Map<String, Object> firstItem = new HashMap<>();
		Map<String, Object> raw = new HashMap<>();
		raw.put("items", List.of(firstItem));

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "", raw, List.of("u0"), "<html>"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("첫 번째 이미지는 REPRESENTATION 타입")
	void syncImagesAndHtml_firstImageIsRepresentation() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		Map<String, Object> firstItem = new HashMap<>();
		Map<String, Object> raw = new HashMap<>();
		raw.put("items", List.of(firstItem));

		client.syncImagesAndHtml(null, "305", raw, List.of("u0", "u1"), "<html>");

		@SuppressWarnings("unchecked") List<Map<String, Object>> images = (List<Map<String, Object>>)firstItem
			.get("images");
		assertThat(images).isNotNull().hasSize(2);
		assertThat(images.get(0).get("imageType")).isEqualTo("REPRESENTATION");
		assertThat(images.get(1).get("imageType")).isEqualTo("DETAIL");
	}

	@Test
	@DisplayName("D-181: 상품수정 PUT 이 200 + code=ERROR 봉투면 예외")
	void syncImagesAndHtml_errorEnvelope_throws() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"상품 수정에 실패했습니다\"}");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", rawWithSingleItem(), List.of("u0"), "<html>"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("상품 수정에 실패했습니다");
	}

	@Test
	@DisplayName("D-181: 상품수정 PUT 응답이 JSON 이 아니면 예외")
	void syncImagesAndHtml_unparsableEnvelope_throws() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn("<html>gateway error</html>");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", rawWithSingleItem(), List.of("u0"), "<html>"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("D-181: 상품수정 PUT 응답에 code 가 없으면 예외")
	void syncImagesAndHtml_missingCode_throws() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn("{\"message\":\"OK\"}");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", rawWithSingleItem(), List.of("u0"), "<html>"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("D-181: 상품수정 PUT 응답 본문이 없으면 예외")
	void syncImagesAndHtml_nullEnvelope_throws() {
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(null);

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", rawWithSingleItem(), List.of("u0"), "<html>"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("D-181: 성공 봉투(code=SUCCESS)면 rawData 를 그대로 반환")
	void syncImagesAndHtml_successEnvelope_returnsRawData() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		Map<String, Object> raw = rawWithSingleItem();

		Map<String, Object> result = client.syncImagesAndHtml(null, "305", raw, List.of("u0"), "<html>");

		assertThat(result).isSameAs(raw);
		assertThat(result.get("requested")).isEqualTo(true);
	}

	@Test
	@DisplayName("D-181: 숫자 code=200 도 성공으로 인정")
	void syncImagesAndHtml_numericSuccessCode_returnsRawData() throws Exception {
		stubJsonParsing();
		when(restClient.put(eq(BASE_PATH), any())).thenReturn("{\"code\":200,\"message\":\"OK\"}");
		Map<String, Object> raw = rawWithSingleItem();

		assertThat(client.syncImagesAndHtml(null, "305", raw, List.of("u0"), "<html>")).isSameAs(raw);
	}

	private Map<String, Object> rawWithSingleItem() {
		Map<String, Object> raw = new HashMap<>();
		raw.put("items", List.of(new HashMap<String, Object>()));
		return raw;
	}

	private void stubJsonParsing() throws Exception {
		ObjectMapper real = new ObjectMapper();
		lenient().when(objectMapper.readTree(anyString()))
			.thenAnswer(invocation -> real.readTree((String)invocation.getArgument(0)));
	}
}
