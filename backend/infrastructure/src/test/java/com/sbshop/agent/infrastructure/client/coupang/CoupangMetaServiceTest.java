package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
		String json = "{\"data\":{\"attributes\":["
			+ "{\"basicRequired\":\"MANDATORY\",\"attributeTypeName\":\"브랜드\",\"dataType\":\"STRING\","
			+ "\"usableUnits\":[]},"
			+ "{\"basicRequired\":\"OPTIONAL\",\"attributeTypeName\":\"원산지\",\"dataType\":\"STRING\","
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

	private void stubMeta(String json) {
		when(restClient.requestWithBody(eq("GET"), anyString(), any())).thenReturn(json);
	}

	private String metaJson(String typeName, String dataType, String units) {
		return "{\"data\":{\"attributes\":[{\"basicRequired\":\"MANDATORY\",\"attributeTypeName\":\"" + typeName
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

	private Product product(BigDecimal capacity, MeasureUnit unit, Integer bundleQuantity) {
		Product product = mock(Product.class);
		lenient().when(product.getLogisticsInfo())
			.thenReturn(LogisticsInfo.builder().bundleQuantity(bundleQuantity).build());
		lenient().when(product.getProductSpec())
			.thenReturn(ProductSpec.builder().capacity(capacity).measureUnit(unit).build());
		return product;
	}
}
