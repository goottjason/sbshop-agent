package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
	private static final String GET_PATH = BASE_PATH + "/305";
	private static final String SUCCESS_ENVELOPE = "{\"code\":\"SUCCESS\",\"message\":\"OK\"}";

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService, new CoupangAttributeValueResolver());
	}

	@Test
	@DisplayName("D-092: id 없는 base로 PUT(requested=true), /approvals 미호출")
	void syncImagesAndHtml_putsWithRequestedTrue() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0", "u1"), "<html>");

		verify(restClient).put(eq(BASE_PATH), any());
		verify(restClient, never()).put(eq(BASE_PATH + "/305/approvals"), any());
		verify(restClient, never()).requestWithBody(anyString(), eq(BASE_PATH + "/305/approvals"), any());
	}

	@Test
	@DisplayName("D-092: PUT 바디에 requested=true 를 넣어 자동 승인요청(임시저장 방지)")
	void syncImagesAndHtml_setsRequestedTrue() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>");

		assertThat(capturedPutBody()).containsEntry("requested", true);
	}

	@Test
	@DisplayName("D-183: 저장된 rawData 에 items 가 있어도 항상 신선한 GET 페이로드로 작업한다(상품 2334 재재게시)")
	void syncImagesAndHtml_alwaysUsesFreshGetPayload() throws Exception {
		stubJsonParsing();
		stubProductGet(null,
			Map.of("attributeTypeName", "수량", "attributeValueName", "수량", "exposed", "EXPOSED"),
			Map.of("attributeTypeName", "개당 중량", "attributeValueName", "", "exposed", "EXPOSED"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		Map<String, Object> sanitizedRawData = storedRawData(
			Map.of("attributeTypeName", "수량", "attributeValueName", "4개", "exposed", "EXPOSED"));

		client.syncImagesAndHtml(product("28", MeasureUnit.G, 4), "305", sanitizedRawData, List.of("u0"), "<html>");

		verify(restClient).get(eq(GET_PATH));
		List<Map<String, Object>> attributes = capturedAttributes();
		assertThat(attributes).hasSize(2);
		assertThat(attributes.get(0)).containsEntry("attributeValueName", "4개");
		assertThat(attributes.get(1)).containsEntry("attributeTypeName", "개당 중량")
			.containsEntry("attributeValueName", "28g");
	}

	@Test
	@DisplayName("rawData={}(items 없음) → GET 으로 전체 페이로드 조회 후 PUT + 승인요청")
	void syncImagesAndHtml_noItems_getsFullPayloadThenPutAndApproval() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(null, "305", new HashMap<>(), List.of("u0"), "<html>");

		InOrder order = inOrder(restClient);
		order.verify(restClient).get(eq(GET_PATH));
		order.verify(restClient).put(eq(BASE_PATH), any());
		verify(restClient, never()).put(eq(BASE_PATH + "/305/approvals"), any());
		verify(restClient, never()).requestWithBody(anyString(), eq(BASE_PATH + "/305/approvals"), any());
	}

	@Test
	@DisplayName("rawData=null(items 없음) → GET 으로 조회 후 PUT + 승인요청")
	void syncImagesAndHtml_nullRawData_getsFullPayload() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(null, "305", null, List.of("u0"), "<html>");

		verify(restClient).get(eq(GET_PATH));
		verify(restClient).put(eq(BASE_PATH), any());
		verify(restClient, never()).put(eq(BASE_PATH + "/305/approvals"), any());
		verify(restClient, never()).requestWithBody(anyString(), eq(BASE_PATH + "/305/approvals"), any());
	}

	@Test
	@DisplayName("GET 응답에 data 가 비어 있으면 IllegalStateException")
	void syncImagesAndHtml_emptyData_throwsIllegalStateException() throws Exception {
		stubJsonParsing();
		when(restClient.get(eq(GET_PATH))).thenReturn("{\"code\":200,\"message\":\"OK\",\"data\":{}}");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("GET 응답이 JSON 이 아니면 IllegalStateException")
	void syncImagesAndHtml_unparsableGetResponse_throwsIllegalStateException() throws Exception {
		stubJsonParsing();
		when(restClient.get(eq(GET_PATH))).thenReturn("<html>gateway error</html>");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("조회 응답 파싱 실패");
	}

	@Test
	@DisplayName("marketItemId 부재 → IllegalStateException 전파")
	void syncImagesAndHtml_missingMarketItemId_throwsIllegalStateException() {
		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "", storedRawData(), List.of("u0"), "<html>"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("첫 번째 이미지는 REPRESENTATION 타입")
	void syncImagesAndHtml_firstImageIsRepresentation() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0", "u1"), "<html>");

		@SuppressWarnings("unchecked") List<Map<String, Object>> images = (List<Map<String, Object>>)capturedItem()
			.get("images");
		assertThat(images).isNotNull().hasSize(2);
		assertThat(images.get(0).get("imageType")).isEqualTo("REPRESENTATION");
		assertThat(images.get(1).get("imageType")).isEqualTo("DETAIL");
	}

	@Test
	@DisplayName("D-181: 상품수정 PUT 이 200 + code=ERROR 봉투면 예외")
	void syncImagesAndHtml_errorEnvelope_throws() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"상품 수정에 실패했습니다\"}");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("상품 수정에 실패했습니다");
	}

	@Test
	@DisplayName("D-181: 상품수정 PUT 응답이 JSON 이 아니면 예외")
	void syncImagesAndHtml_unparsableEnvelope_throws() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn("<html>gateway error</html>");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("D-181: 상품수정 PUT 응답에 code 가 없으면 예외")
	void syncImagesAndHtml_missingCode_throws() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn("{\"message\":\"OK\"}");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("D-181: 상품수정 PUT 응답 본문이 없으면 예외")
	void syncImagesAndHtml_nullEnvelope_throws() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(null);

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("D-181: 성공 봉투(code=SUCCESS)면 PUT 한 신선 페이로드를 반환")
	void syncImagesAndHtml_successEnvelope_returnsPutPayload() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		Map<String, Object> result = client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>");

		assertThat(result).isSameAs(capturedPutBody());
		assertThat(result).containsEntry("requested", true);
	}

	@Test
	@DisplayName("D-181: 숫자 code=200 도 성공으로 인정")
	void syncImagesAndHtml_numericSuccessCode_returnsPutPayload() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn("{\"code\":200,\"message\":\"OK\"}");

		Map<String, Object> result = client.syncImagesAndHtml(null, "305", storedRawData(), List.of("u0"), "<html>");

		assertThat(result).isSameAs(capturedPutBody());
	}

	@Test
	@DisplayName("D-182: 빈 값 속성은 드롭하고 자리표시 속성은 상품 값으로 재충전해 PUT 한다")
	void syncImagesAndHtml_sanitizesAttributesBeforePut() throws Exception {
		stubJsonParsing();
		stubProductGet(null,
			Map.of("attributeTypeName", "브랜드", "attributeValueName", "", "exposed", "NONE"),
			Map.of("attributeTypeName", "유통기한", "attributeValueName", "", "exposed", "NONE"),
			Map.of("attributeTypeName", "수량", "attributeValueName", "수량", "exposed", "EXPOSED"),
			Map.of("attributeTypeName", "개당 용량/중량/정", "attributeValueName", "용량", "exposed", "EXPOSED"),
			Map.of("attributeTypeName", "모델명", "attributeValueName", "Osteocare Liquid"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(product(), "305", storedRawData(), List.of("u0"), "<html>");

		List<Map<String, Object>> attributes = capturedAttributes();
		assertThat(attributes).hasSize(3);
		assertThat(attributes.get(0)).containsEntry("attributeTypeName", "수량")
			.containsEntry("attributeValueName", "3개")
			.containsEntry("exposed", "EXPOSED");
		assertThat(attributes.get(1)).containsEntry("attributeTypeName", "개당 용량/중량/정")
			.containsEntry("attributeValueName", "200ml")
			.containsEntry("exposed", "EXPOSED");
		assertThat(attributes.get(2)).containsEntry("attributeTypeName", "모델명")
			.containsEntry("attributeValueName", "Osteocare Liquid");
	}

	@Test
	@DisplayName("D-182: attributeValueName 이 null 인 속성도 드롭한다")
	void syncImagesAndHtml_nullAttributeValue_dropped() throws Exception {
		stubJsonParsing();
		Map<String, String> blank = new HashMap<>();
		blank.put("attributeTypeName", "브랜드");
		blank.put("attributeValueName", null);
		stubProductGet(null, blank,
			Map.of("attributeTypeName", "모델명", "attributeValueName", "Osteocare Liquid"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(product(), "305", storedRawData(), List.of("u0"), "<html>");

		List<Map<String, Object>> attributes = capturedAttributes();
		assertThat(attributes).hasSize(1);
		assertThat(attributes.get(0)).containsEntry("attributeTypeName", "모델명");
	}

	@Test
	@DisplayName("D-182: 의미 있는 값을 가진 속성은 그대로 유지한다")
	void syncImagesAndHtml_meaningfulAttributes_keptAsIs() throws Exception {
		stubJsonParsing();
		stubProductGet(null,
			Map.of("attributeTypeName", "수량", "attributeValueName", "12개"),
			Map.of("attributeTypeName", "개당 용량/중량/정", "attributeValueName", "500ml"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(product(), "305", storedRawData(), List.of("u0"), "<html>");

		List<Map<String, Object>> attributes = capturedAttributes();
		assertThat(attributes).hasSize(2);
		assertThat(attributes.get(0)).containsEntry("attributeValueName", "12개");
		assertThat(attributes.get(1)).containsEntry("attributeValueName", "500ml");
	}

	@Test
	@DisplayName("D-182: attributes 키가 없는 아이템은 건드리지 않는다")
	void syncImagesAndHtml_withoutAttributes_itemUntouched() throws Exception {
		stubJsonParsing();
		stubProductGet(null);
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(product(), "305", storedRawData(), List.of("u0"), "<html>");

		assertThat(capturedItem()).doesNotContainKey("attributes");
	}

	@Test
	@DisplayName("D-183: 카테고리 usableUnits 를 대조해 재충전 단위를 고른다")
	void syncImagesAndHtml_refillUsesCategoryUsableUnits() throws Exception {
		stubJsonParsing();
		stubProductGet(1001L, Map.of("attributeTypeName", "용량", "attributeValueName", "용량"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		when(metaService.getUsableUnits(1001L)).thenReturn(Map.of("용량", List.of("정", "캡슐")));

		client.syncImagesAndHtml(product(), "305", storedRawData(), List.of("u0"), "<html>");

		assertThat(capturedAttributes().get(0)).containsEntry("attributeValueName", "200정");
	}

	@Test
	@DisplayName("D-183: 자리표시가 여러 개여도 카테고리 메타는 한 번만 조회한다")
	void syncImagesAndHtml_loadsCategoryMetaOnce() throws Exception {
		stubJsonParsing();
		stubProductGet(1001L,
			Map.of("attributeTypeName", "용량", "attributeValueName", "용량"),
			Map.of("attributeTypeName", "수량", "attributeValueName", "수량"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		when(metaService.getUsableUnits(1001L)).thenReturn(Map.of("용량", List.of("ml")));

		client.syncImagesAndHtml(product(), "305", storedRawData(), List.of("u0"), "<html>");

		verify(metaService, times(1)).getUsableUnits(1001L);
		List<Map<String, Object>> attributes = capturedAttributes();
		assertThat(attributes).hasSize(2);
		assertThat(attributes.get(0)).containsEntry("attributeValueName", "200ml");
		assertThat(attributes.get(1)).containsEntry("attributeValueName", "3개");
	}

	@Test
	@DisplayName("D-183: 카테고리 메타 조회가 실패해도 고정 단위로 재충전하고 PUT 은 계속한다")
	void syncImagesAndHtml_metaFailure_fallsBackToFixedUnit() throws Exception {
		stubJsonParsing();
		stubProductGet(1001L,
			Map.of("attributeTypeName", "개당 용량/중량/정", "attributeValueName", "용량"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);
		when(metaService.getUsableUnits(1001L)).thenThrow(new IllegalStateException("메타 API 장애"));

		client.syncImagesAndHtml(product(), "305", storedRawData(), List.of("u0"), "<html>");

		assertThat(capturedAttributes().get(0)).containsEntry("attributeValueName", "200ml");
	}

	@Test
	@DisplayName("D-183: displayCategoryCode 가 없으면 메타를 조회하지 않고 고정 단위로 재충전한다")
	void syncImagesAndHtml_withoutCategoryCode_skipsMetaLookup() throws Exception {
		stubJsonParsing();
		stubProductGet(null,
			Map.of("attributeTypeName", "개당 용량/중량/정", "attributeValueName", "용량"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(product(), "305", storedRawData(), List.of("u0"), "<html>");

		verify(metaService, never()).getUsableUnits(any());
		assertThat(capturedAttributes().get(0)).containsEntry("attributeValueName", "200ml");
	}

	@Test
	@DisplayName("D-183: 빈 값이라도 EXPOSED 필수옵션은 단위 계열이 맞으면 재충전한다(상품 2334 형상)")
	void syncImagesAndHtml_emptyExposedAttribute_refilledWhenUnitFamilyMatches() throws Exception {
		stubJsonParsing();
		stubProductGet(null,
			Map.of("attributeTypeName", "수량", "attributeValueName", "수량", "exposed", "EXPOSED"),
			Map.of("attributeTypeName", "개당 용량", "attributeValueName", "", "exposed", "EXPOSED"),
			Map.of("attributeTypeName", "개당 중량", "attributeValueName", "", "exposed", "EXPOSED"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(product("28", MeasureUnit.G, 4), "305", storedRawData(), List.of("u0"), "<html>");

		List<Map<String, Object>> attributes = capturedAttributes();
		assertThat(attributes).hasSize(2);
		assertThat(attributes.get(0)).containsEntry("attributeTypeName", "수량")
			.containsEntry("attributeValueName", "4개");
		assertThat(attributes.get(1)).containsEntry("attributeTypeName", "개당 중량")
			.containsEntry("attributeValueName", "28g");
	}

	@Test
	@DisplayName("D-183: 빈 값이 EXPOSED 가 아니면(NONE·키 부재) 기존대로 드롭한다")
	void syncImagesAndHtml_emptyNonExposedAttribute_dropped() throws Exception {
		stubJsonParsing();
		stubProductGet(null,
			Map.of("attributeTypeName", "개당 중량", "attributeValueName", "", "exposed", "NONE"),
			Map.of("attributeTypeName", "개당 용량", "attributeValueName", ""),
			Map.of("attributeTypeName", "모델명", "attributeValueName", "Osteocare Liquid"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(product("28", MeasureUnit.G, 4), "305", storedRawData(), List.of("u0"), "<html>");

		List<Map<String, Object>> attributes = capturedAttributes();
		assertThat(attributes).hasSize(1);
		assertThat(attributes.get(0)).containsEntry("attributeTypeName", "모델명");
	}

	@Test
	@DisplayName("D-183: 병합 라벨 자리표시는 토큰 하나만 단위 계열과 맞아도 재충전한다(3110 ML·2591 TABLET)")
	void syncImagesAndHtml_mergedLabelPlaceholder_refilledForBothUnitFamilies() throws Exception {
		stubJsonParsing();
		stubProductGet(null,
			Map.of("attributeTypeName", "개당 용량/중량/정", "attributeValueName", "용량", "exposed", "EXPOSED"));
		when(restClient.put(eq(BASE_PATH), any())).thenReturn(SUCCESS_ENVELOPE);

		client.syncImagesAndHtml(product("30", MeasureUnit.TABLET, 3), "305", storedRawData(), List.of("u0"),
			"<html>");

		assertThat(capturedAttributes().get(0)).containsEntry("attributeValueName", "30정");
	}

	@SafeVarargs
	private void stubProductGet(Long categoryCode, Map<String, String>... attributes) throws Exception {
		Map<String, Object> firstItem = new HashMap<>();
		if (attributes.length > 0) {
			firstItem.put("attributes", List.of(attributes));
		}
		Map<String, Object> data = new HashMap<>();
		data.put("items", List.of(firstItem));
		if (categoryCode != null) {
			data.put("displayCategoryCode", categoryCode);
		}
		Map<String, Object> envelope = new HashMap<>();
		envelope.put("code", 200);
		envelope.put("message", "OK");
		envelope.put("data", data);
		when(restClient.get(eq(GET_PATH))).thenReturn(new ObjectMapper().writeValueAsString(envelope));
	}

	@SafeVarargs
	private Map<String, Object> storedRawData(Map<String, String>... attributes) {
		Map<String, Object> firstItem = new HashMap<>();
		firstItem.put("attributes", List.of(attributes));
		Map<String, Object> raw = new HashMap<>();
		raw.put("items", List.of(firstItem));
		return raw;
	}

	private Map<String, Object> capturedPutBody() {
		ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
		verify(restClient).put(eq(BASE_PATH), body.capture());
		@SuppressWarnings("unchecked") Map<String, Object> sent = (Map<String, Object>)body.getValue();
		return sent;
	}

	private Map<String, Object> capturedItem() {
		@SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>)capturedPutBody()
			.get("items");
		return items.get(0);
	}

	private List<Map<String, Object>> capturedAttributes() {
		@SuppressWarnings("unchecked") List<Map<String, Object>> attributes = (List<Map<String, Object>>)capturedItem()
			.get("attributes");
		return attributes;
	}

	private Product product() {
		return product("200", MeasureUnit.ML, 3);
	}

	private Product product(String capacity, MeasureUnit measureUnit, int bundleQuantity) {
		Product product = mock(Product.class);
		lenient().when(product.getLogisticsInfo())
			.thenReturn(LogisticsInfo.builder().bundleQuantity(bundleQuantity).build());
		lenient().when(product.getProductSpec()).thenReturn(ProductSpec.builder()
			.capacity(new BigDecimal(capacity)).measureUnit(measureUnit).build());
		return product;
	}

	private void stubJsonParsing() throws Exception {
		ObjectMapper real = new ObjectMapper();
		lenient().when(objectMapper.readTree(anyString()))
			.thenAnswer(invocation -> real.readTree((String)invocation.getArgument(0)));
		lenient().when(objectMapper.convertValue(any(JsonNode.class), any(TypeReference.class)))
			.thenAnswer(invocation -> real.convertValue(invocation.getArgument(0),
				new TypeReference<Map<String, Object>>() {}));
	}
}
