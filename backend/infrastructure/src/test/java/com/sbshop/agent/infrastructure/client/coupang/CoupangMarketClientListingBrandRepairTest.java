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
import com.sbshop.agent.core.domain.product.vo.SourcingInfo;
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
class CoupangMarketClientListingBrandRepairTest {

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
	private static final String ID = "14300000001";

	private static final List<CoupangAttributeMeta> META = List.of(
		new CoupangAttributeMeta("개당 캡슐/정", "NUMBER", List.of("정"), true, true, "1"),
		new CoupangAttributeMeta("수량", "NUMBER", List.of("개"), true, true, "NONE"));

	@BeforeEach
	void setUp() throws Exception {
		client = new CoupangMarketClient(properties, new ObjectMapper(), restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService, new CoupangAttributeValueResolver());
		lenient().when(metaService.getAttributeMetas(58920L)).thenReturn(META);
	}

	private Product product(String brand, String manufacturer) {
		Product p = mock(Product.class);
		lenient().when(p.getBrand()).thenReturn(brand);
		lenient().when(p.getSourcingInfo()).thenReturn(SourcingInfo.builder().manufacturer(manufacturer).build());
		return p;
	}

	private void stubGet(String brand, String manufacture) {
		when(restClient.get(BASE + "/" + ID)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{"
			+ "\"sellerProductId\":14300000001,\"statusName\":\"승인반려\",\"sellerProductName\":\"닥터스베스트 비타민 120정\","
			+ "\"displayCategoryCode\":58920,\"brand\":\"" + brand + "\",\"manufacture\":\"" + manufacture + "\","
			+ "\"items\":[{\"itemName\":\"1개\",\"attributes\":["
			+ "{\"attributeTypeName\":\"개당 캡슐/정\",\"attributeValueName\":\"120정\",\"exposed\":\"EXPOSED\",\"editable\":true},"
			+ "{\"attributeTypeName\":\"수량\",\"attributeValueName\":\"1개\",\"exposed\":\"EXPOSED\",\"editable\":true}"
			+ "]}]}}");
	}

	@Test
	@DisplayName("D-341: 자체브랜드·자체제작은 DB 브랜드·제조사로 바꾼다")
	void replacesSelfBrand() {
		stubGet("자체브랜드", "자체제작");

		ListingAttributeRepair result = client.repairListingAttributes(product("닥터스베스트", "Doctor's Best"), ID,
			false);

		assertThat(result.outcome()).isEqualTo(ListingAttributeRepairOutcome.DRY_RUN);
		assertThat(result.fieldChanges()).containsExactly("brand: 자체브랜드→닥터스베스트",
			"manufacture: 자체제작→Doctor's Best");
		verify(restClient, never()).put(anyString(), any());
	}

	@Test
	@DisplayName("D-341: 공백 제거 비교로 다르면 DB 브랜드로 바꾼다")
	void replacesDifferentBrand() {
		stubGet("노르딕 내추럴스", "Nordic Naturals Inc");

		ListingAttributeRepair result = client.repairListingAttributes(product("노르딕내츄럴스", null), ID, false);

		assertThat(result.fieldChanges()).containsExactly("brand: 노르딕 내추럴스→노르딕내츄럴스");
	}

	@Test
	@DisplayName("D-341: 공백만 다르거나 같으면 바꾸지 않는다")
	void keepsSameBrand() {
		stubGet("닥터스 베스트", "Doctor's Best Inc");

		ListingAttributeRepair result = client.repairListingAttributes(product("닥터스베스트", "Doctor's Best"), ID,
			false);

		assertThat(result.fieldChanges()).isEmpty();
	}

	@Test
	@DisplayName("D-341: 제조사가 자체제작인데 DB 제조사가 없으면 DB 브랜드로 쓴다")
	void manufactureFallsBackToBrand() {
		stubGet("닥터스베스트", "자체제작");

		ListingAttributeRepair result = client.repairListingAttributes(product("닥터스베스트", " "), ID, false);

		assertThat(result.fieldChanges()).containsExactly("manufacture: 자체제작→닥터스베스트");
	}

	@Test
	@DisplayName("D-341: DB 브랜드가 없으면 brand·manufacture 를 건드리지 않는다")
	void nullDbBrandKeepsBoth() {
		stubGet("자체브랜드", "자체제작");

		ListingAttributeRepair result = client.repairListingAttributes(product(null, "Doctor's Best"), ID, false);

		assertThat(result.fieldChanges()).isEmpty();
	}

	@Test
	@DisplayName("D-341: 제출하면 교정된 brand·manufacture 를 같은 PUT 에 담는다")
	@SuppressWarnings("unchecked")
	void submitCarriesBrandFields() {
		stubGet("자체브랜드", "");
		when(restClient.put(eq(BASE), any())).thenReturn("{\"code\":\"SUCCESS\",\"message\":\"\"}");

		ListingAttributeRepair result = client.repairListingAttributes(product("닥터스베스트", null), ID, true);

		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq(BASE), body.capture());
		assertThat(body.getValue()).containsEntry("brand", "닥터스베스트").containsEntry("manufacture", "닥터스베스트")
			.containsEntry("requested", true);
		assertThat(result.outcome()).isEqualTo(ListingAttributeRepairOutcome.SUBMITTED);
		assertThat(result.fieldChanges()).containsExactly("brand: 자체브랜드→닥터스베스트", "manufacture: →닥터스베스트");
	}
}
