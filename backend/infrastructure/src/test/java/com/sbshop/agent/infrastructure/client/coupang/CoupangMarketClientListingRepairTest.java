package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.ListingAttributeRepair;
import com.sbshop.agent.core.domain.market.client.dto.ListingAttributeRepairOutcome;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangAttributeMeta;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangMarketClientListingRepairTest {

	@Mock
	private CoupangProperties properties;
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

	private static final String BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
	private static final String GET_PATH = BASE + "/14300000001";

	private static final List<CoupangAttributeMeta> META = List.of(
		new CoupangAttributeMeta("개당 캡슐/정", "NUMBER", List.of("정", "회분"), true, true, "1"),
		new CoupangAttributeMeta("개당 중량", "NUMBER", List.of("g", "kg"), true, true, "1"),
		new CoupangAttributeMeta("수량", "NUMBER", List.of("개", "박스"), true, true, "NONE"));

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, new ObjectMapper(), restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService, new CoupangAttributeValueResolver());
	}

	private Product product(String originalName) {
		Product p = mock(Product.class);
		lenient().when(p.getOriginalName()).thenReturn(originalName);
		return p;
	}

	private void stubGet(String statusName, String sellerProductName) {
		when(restClient.get(GET_PATH)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{"
			+ "\"sellerProductId\":14300000001,\"statusName\":\"" + statusName + "\","
			+ "\"sellerProductName\":\"" + sellerProductName + "\",\"displayCategoryCode\":58920,"
			+ "\"items\":[{\"itemName\":\"1개\",\"attributes\":["
			+ "{\"attributeTypeName\":\"개당 용량/중량/정\",\"attributeValueName\":\"90정\",\"exposed\":\"EXPOSED\",\"editable\":true},"
			+ "{\"attributeTypeName\":\"개당 중량\",\"attributeValueName\":\"\",\"exposed\":\"NONE\",\"editable\":true}"
			+ "]}]}}");
	}

	@Test
	@DisplayName("D-340: 승인반려가 아니면 쓰지 않고 SKIPPED_STATUS")
	void skipsNonRejected() throws Exception {
		stubGet("승인완료", "나우푸드 비타민 D 90정");

		ListingAttributeRepair result = client.repairListingAttributes(product(null), "14300000001", true);

		assertThat(result.outcome()).isEqualTo(ListingAttributeRepairOutcome.SKIPPED_STATUS);
		assertThat(result.statusBefore()).isEqualTo("승인완료");
		verify(restClient, never()).put(anyString(), any());
		verify(metaService, never()).getAttributeMetas(any());
	}

	@Test
	@DisplayName("D-340: 추출 불가 그룹이 있으면 쓰지 않고 UNRESOLVED")
	void unresolvedDoesNotWrite() throws Exception {
		stubGet("승인반려", "나우푸드 프로바이오틱");
		when(metaService.getAttributeMetas(58920L)).thenReturn(META);

		ListingAttributeRepair result = client.repairListingAttributes(product("NOW Foods, Probiotic"),
			"14300000001", true);

		assertThat(result.outcome()).isEqualTo(ListingAttributeRepairOutcome.UNRESOLVED);
		assertThat(result.missing()).contains("개당 캡슐/정", "개당 중량");
		verify(restClient, never()).put(anyString(), any());
	}

	@Test
	@DisplayName("D-340: 미리보기는 보정 내역만 돌려주고 쓰지 않는다")
	void dryRunDoesNotWrite() throws Exception {
		stubGet("승인반려", "나우푸드 비타민 D");
		when(metaService.getAttributeMetas(58920L)).thenReturn(META);

		ListingAttributeRepair result = client.repairListingAttributes(product("NOW Foods, Vitamin D, 120 Softgels"),
			"14300000001", false);

		assertThat(result.outcome()).isEqualTo(ListingAttributeRepairOutcome.DRY_RUN);
		assertThat(result.filled()).contains("개당 캡슐/정=120정", "수량=1개");
		assertThat(result.removed()).contains("개당 용량/중량/정(폐지)", "개당 중량(빈값)");
		verify(restClient, never()).put(anyString(), any());
	}

	@Test
	@DisplayName("D-340: 제출하면 requested=true 와 보정된 attributes 로 PUT 하고 응답 경고를 detail 에 남긴다")
	@SuppressWarnings("unchecked")
	void submitPutsRepairedPayload() throws Exception {
		stubGet("승인반려", "나우푸드 비타민 D 120정");
		when(metaService.getAttributeMetas(58920L)).thenReturn(META);
		when(restClient.put(eq(BASE), any())).thenReturn("{\"code\":\"SUCCESS\",\"message\":\"필수 구매 옵션이 존재하지 않습니다\","
			+ "\"details\":\"'개당 용량/중량/정'은(는) 유효하지 않은 구매 옵션입니다\",\"data\":14300000001}");

		ListingAttributeRepair result = client.repairListingAttributes(product(null), "14300000001", true);

		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq(BASE), body.capture());
		assertThat(body.getValue()).containsEntry("requested", true);
		List<Map<String, Object>> items = (List<Map<String, Object>>)body.getValue().get("items");
		List<Map<String, Object>> attributes = (List<Map<String, Object>>)items.get(0).get("attributes");
		assertThat(attributes).extracting(a -> a.get("attributeTypeName"))
			.containsExactlyInAnyOrder("개당 캡슐/정", "수량");
		assertThat(attributes).extracting(a -> a.get("attributeValueName")).contains("120정", "1개");
		assertThat(result.outcome()).isEqualTo(ListingAttributeRepairOutcome.SUBMITTED);
		assertThat(result.detail()).contains("필수 구매 옵션이 존재하지 않습니다").contains("유효하지 않은 구매 옵션");
	}

	@Test
	@DisplayName("D-340: 제출 응답이 SUCCESS 가 아니면 FAILED")
	void submitFailureEnvelope() throws Exception {
		stubGet("승인반려", "나우푸드 비타민 D 120정");
		when(metaService.getAttributeMetas(58920L)).thenReturn(META);
		when(restClient.put(eq(BASE), any())).thenReturn("{\"code\":\"ERROR\",\"message\":\"옵션 항목 확인\"}");

		ListingAttributeRepair result = client.repairListingAttributes(product(null), "14300000001", true);

		assertThat(result.outcome()).isEqualTo(ListingAttributeRepairOutcome.FAILED);
		assertThat(result.detail()).contains("옵션 항목 확인");
	}
}
