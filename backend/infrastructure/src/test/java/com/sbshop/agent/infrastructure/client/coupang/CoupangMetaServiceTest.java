package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.dto.CategoryMetaResult;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload.Item.Attribute;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangMetaServiceTest {

	@Mock
	private CoupangRestClient restClient;

	private CoupangMetaService metaService;

	@BeforeEach
	void setUp() {
		metaService = new CoupangMetaService(restClient, new ObjectMapper(), new CoupangAttributeValueResolver());
	}

	@Test
	@DisplayName("D-183: '수량'은 묶음수량 그대로 '3개' — capacity 를 곱하지 않는다")
	void quantityAttribute_usesBundleQuantityOnly() throws Exception {
		stubMeta(metaJson("수량", "NUMBER", "\"개\",\"정\""));

		CategoryMetaResult result = metaService.getCategoryMeta(1001L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(valueOf(result, "수량")).isEqualTo("3개");
	}

	@Test
	@DisplayName("D-183: '총 캡슐/정 수량'은 capacity×묶음수량 '90정'")
	void totalCapsuleAttribute_multipliesCapacityByBundle() throws Exception {
		stubMeta(metaJson("총 캡슐/정 수량", "NUMBER", "\"정\",\"개\""));

		CategoryMetaResult result = metaService.getCategoryMeta(1001L,
			product(new BigDecimal("30"), MeasureUnit.TABLET, 3));

		assertThat(valueOf(result, "총 캡슐/정 수량")).isEqualTo("90정");
	}

	@Test
	@DisplayName("D-183: '총 용량'은 capacity×묶음수량 '600ml'")
	void totalCapacityAttribute_multipliesCapacityByBundle() throws Exception {
		stubMeta(metaJson("총 용량", "NUMBER", "\"ml\",\"L\""));

		CategoryMetaResult result = metaService.getCategoryMeta(1001L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(valueOf(result, "총 용량")).isEqualTo("600ml");
	}

	@Test
	@DisplayName("D-183: '용량'은 개당 capacity '200ml'")
	void capacityAttribute_usesCapacityOnly() throws Exception {
		stubMeta(metaJson("용량", "NUMBER", "\"ml\",\"L\""));

		CategoryMetaResult result = metaService.getCategoryMeta(1001L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(valueOf(result, "용량")).isEqualTo("200ml");
	}

	@Test
	@DisplayName("D-183: bundleQuantity 가 null 이어도 NPE 없이 기본 1 로 계산한다")
	void nullBundleQuantity_doesNotThrow() throws Exception {
		stubMeta(metaJson("수량", "NUMBER", "\"개\""));
		Product product = mock(Product.class);
		lenient().when(product.getLogisticsInfo()).thenReturn(LogisticsInfo.builder().build());
		lenient().when(product.getProductSpec()).thenReturn(ProductSpec.builder()
			.capacity(new BigDecimal("200")).measureUnit(MeasureUnit.ML).build());

		CategoryMetaResult result = metaService.getCategoryMeta(1001L, product);

		assertThat(valueOf(result, "수량")).isEqualTo("1개");
	}

	@Test
	@DisplayName("NUMBER 가 아닌 필수 속성은 '상세페이지 참조', MANDATORY 아닌 속성은 제외한다")
	void nonNumberAndOptionalAttributes_unchanged() throws Exception {
		String json = "{\"code\":\"SUCCESS\",\"data\":{\"attributes\":["
			+ "{\"required\":\"MANDATORY\",\"attributeTypeName\":\"브랜드\",\"dataType\":\"STRING\","
			+ "\"usableUnits\":[]},"
			+ "{\"required\":\"OPTIONAL\",\"attributeTypeName\":\"원산지\",\"dataType\":\"STRING\","
			+ "\"usableUnits\":[]}],"
			+ "\"noticeCategories\":[{\"noticeCategoryName\":\"건강기능식품\",\"noticeCategoryDetailNames\":"
			+ "[{\"noticeCategoryDetailName\":\"제품명\"}]}]}}";
		stubMeta(json);

		CategoryMetaResult result = metaService.getCategoryMeta(1001L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(result.attributes()).hasSize(1);
		assertThat(result.attributes().get(0).attributeTypeName()).isEqualTo("브랜드");
		assertThat(result.attributes().get(0).attributeValueName()).isEqualTo("상세페이지 참조");
		assertThat(result.attributes().get(0).exposed()).isEqualTo("NONE");
		assertThat(result.notices()).hasSize(1);
		assertThat(result.notices().get(0).noticeCategoryName()).isEqualTo("건강기능식품");
		assertThat(result.notices().get(0).content()).isEqualTo("상품상세페이지 참조");
	}

	@Test
	@DisplayName("D-185: 카테고리 메타는 현행 seller_api 경로로 조회한다")
	void categoryMeta_requestsCurrentSellerApiPath() throws Exception {
		stubMeta(metaJson("수량", "NUMBER", "\"개\""));

		metaService.getCategoryMeta(73134L, product(new BigDecimal("200"), MeasureUnit.ML, 3));

		verify(restClient).requestWithBody(eq("GET"),
			eq("/v2/providers/seller_api/apis/api/v1/marketplace/meta"
				+ "/category-related-metas/display-category-codes/73134"),
			isNull());
	}

	@Test
	@DisplayName("D-185: 신 응답은 basicRequired 없이 required=MANDATORY 로 필수 여부를 표시한다")
	void mandatoryAttributes_readRequiredField() throws Exception {
		String json = "{\"code\":\"SUCCESS\",\"data\":{\"attributes\":["
			+ "{\"required\":\"MANDATORY\",\"attributeTypeName\":\"브랜드\",\"dataType\":\"STRING\","
			+ "\"inputType\":\"INPUT\",\"groupNumber\":\"NONE\",\"exposed\":\"EXPOSED\",\"usableUnits\":[]},"
			+ "{\"required\":\"OPTIONAL\",\"attributeTypeName\":\"원산지\",\"dataType\":\"STRING\","
			+ "\"inputType\":\"INPUT\",\"groupNumber\":\"NONE\",\"exposed\":\"EXPOSED\",\"usableUnits\":[]}],"
			+ "\"noticeCategories\":[],\"certifications\":[],\"allowedOfferConditions\":[]}}";
		stubMeta(json);

		CategoryMetaResult result = metaService.getCategoryMeta(73134L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(result.attributes()).hasSize(1);
		assertThat(result.attributes().get(0).attributeTypeName()).isEqualTo("브랜드");
	}

	@Test
	@DisplayName("D-195: 메타의 exposed 값을 그대로 전달한다 — 필수 구매옵션은 EXPOSED")
	void exposedAttribute_keepsMetaValue() throws Exception {
		String json = "{\"code\":\"SUCCESS\",\"data\":{\"attributes\":["
			+ "{\"required\":\"MANDATORY\",\"attributeTypeName\":\"수량\",\"dataType\":\"NUMBER\","
			+ "\"exposed\":\"EXPOSED\",\"usableUnits\":[\"개\"]},"
			+ "{\"required\":\"MANDATORY\",\"attributeTypeName\":\"브랜드\",\"dataType\":\"STRING\","
			+ "\"exposed\":\"NONE\",\"usableUnits\":[]}],"
			+ "\"noticeCategories\":[]}}";
		stubMeta(json);

		CategoryMetaResult result = metaService.getCategoryMeta(73134L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(exposedOf(result, "수량")).isEqualTo("EXPOSED");
		assertThat(exposedOf(result, "브랜드")).isEqualTo("NONE");
	}

	@Test
	@DisplayName("D-195: exposed 필드가 없는 속성은 NONE 으로 폴백한다")
	void missingExposed_fallsBackToNone() throws Exception {
		stubMeta(metaJson("수량", "NUMBER", "\"개\""));

		CategoryMetaResult result = metaService.getCategoryMeta(73134L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(exposedOf(result, "수량")).isEqualTo("NONE");
	}

	@Test
	@DisplayName("D-196: 같은 그룹의 필수 구매옵션은 택1 — 정제 상품은 '개당 캡슐/정'만 남는다")
	void groupedAttributes_pickUnitFamilyMatch_forTablet() throws Exception {
		stubMeta(groupedMeta());

		CategoryMetaResult result = metaService.getCategoryMeta(73134L,
			product(new BigDecimal("30"), MeasureUnit.TABLET, 3));

		assertThat(result.attributes()).extracting(Attribute::attributeTypeName)
			.containsExactly("개당 캡슐/정", "수량");
	}

	@Test
	@DisplayName("D-196: 액상 상품이면 같은 그룹에서 '개당 용량'이 선택된다")
	void groupedAttributes_pickUnitFamilyMatch_forVolume() throws Exception {
		stubMeta(groupedMeta());

		CategoryMetaResult result = metaService.getCategoryMeta(73134L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(result.attributes()).extracting(Attribute::attributeTypeName)
			.containsExactly("개당 용량", "수량");
	}

	@Test
	@DisplayName("D-196: 단위 계열이 맞는 항목이 없으면 그룹 첫 항목을 남긴다 — 등록 필수라 드롭 불가")
	void groupedAttributes_fallBackToFirst() throws Exception {
		String json = metaOf(
			attrJson("개당 용량", "1", "\"ml\""),
			attrJson("개당 캡슐/정", "1", "\"정\""));
		stubMeta(json);

		CategoryMetaResult result = metaService.getCategoryMeta(73134L,
			product(new BigDecimal("500"), MeasureUnit.G, 3));

		assertThat(result.attributes()).extracting(Attribute::attributeTypeName)
			.containsExactly("개당 용량");
	}

	@Test
	@DisplayName("D-196: 그룹이 없는 항목은 EXPOSED 여도 전부 유지한다")
	void ungroupedAttributes_allKept() throws Exception {
		String json = metaOf(
			attrJson("개당 용량", "", "\"ml\""),
			attrJson("개당 중량", "NONE", "\"g\""));
		stubMeta(json);

		CategoryMetaResult result = metaService.getCategoryMeta(73134L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(result.attributes()).extracting(Attribute::attributeTypeName)
			.containsExactly("개당 용량", "개당 중량");
	}

	@Test
	@DisplayName("D-196: 그룹이 달라도 그룹마다 하나씩은 남는다")
	void separateGroups_keepOneEach() throws Exception {
		String json = metaOf(
			attrJson("개당 용량", "1", "\"ml\""),
			attrJson("개당 중량", "1", "\"g\""),
			attrJson("총 용량", "2", "\"ml\""),
			attrJson("총 중량", "2", "\"g\""));
		stubMeta(json);

		CategoryMetaResult result = metaService.getCategoryMeta(73134L,
			product(new BigDecimal("200"), MeasureUnit.ML, 3));

		assertThat(result.attributes()).extracting(Attribute::attributeTypeName)
			.containsExactly("개당 용량", "총 용량");
	}

	@Test
	@DisplayName("D-185: usableUnits 맵은 신 응답 형상에서도 속성명 기준으로 추출한다")
	void usableUnits_extractedFromCurrentShape() throws Exception {
		stubMeta(metaJson("총 용량", "NUMBER", "\"ml\",\"L\""));

		assertThat(metaService.getUsableUnits(73134L)).containsEntry("총 용량", List.of("ml", "L"));
	}

	private void stubMeta(String json) {
		when(restClient.requestWithBody(eq("GET"), anyString(), any())).thenReturn(json);
	}

	private String metaJson(String typeName, String dataType, String units) {
		return "{\"code\":\"SUCCESS\",\"data\":{\"attributes\":["
			+ "{\"required\":\"MANDATORY\",\"attributeTypeName\":\"" + typeName
			+ "\",\"dataType\":\"" + dataType + "\",\"usableUnits\":[" + units + "]}],"
			+ "\"noticeCategories\":[{\"noticeCategoryName\":\"건강기능식품\",\"noticeCategoryDetailNames\":"
			+ "[{\"noticeCategoryDetailName\":\"제품명\"}]}]}}";
	}

	private String valueOf(CategoryMetaResult result, String typeName) {
		return result.attributes().stream()
			.filter(attribute -> typeName.equals(attribute.attributeTypeName()))
			.map(Attribute::attributeValueName)
			.findFirst()
			.orElse(null);
	}

	private String groupedMeta() {
		return metaOf(
			attrJson("개당 캡슐/정", "1", "\"정\",\"캡슐\""),
			attrJson("개당 중량", "1", "\"g\",\"mg\""),
			attrJson("개당 용량", "1", "\"ml\",\"L\""),
			attrJson("수량", "NONE", "\"개\""));
	}

	private String attrJson(String typeName, String groupNumber, String units) {
		return "{\"required\":\"MANDATORY\",\"attributeTypeName\":\"" + typeName
			+ "\",\"dataType\":\"NUMBER\",\"exposed\":\"EXPOSED\",\"groupNumber\":\"" + groupNumber
			+ "\",\"usableUnits\":[" + units + "]}";
	}

	private String metaOf(String... attrs) {
		return "{\"code\":\"SUCCESS\",\"data\":{\"attributes\":[" + String.join(",", attrs) + "],"
			+ "\"noticeCategories\":[]}}";
	}

	private String exposedOf(CategoryMetaResult result, String typeName) {
		return result.attributes().stream()
			.filter(attribute -> typeName.equals(attribute.attributeTypeName()))
			.map(Attribute::exposed)
			.findFirst()
			.orElse(null);
	}

	private Product product(BigDecimal capacity, MeasureUnit unit, Integer bundleQuantity) {
		Product product = mock(Product.class);
		lenient().when(product.getLogisticsInfo())
			.thenReturn(LogisticsInfo.builder().bundleQuantity(bundleQuantity).build());
		lenient().when(product.getProductSpec())
			.thenReturn(ProductSpec.builder().capacity(capacity).measureUnit(unit).build());
		return product;
	}
}
