package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24BrandCodeResolver;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24OriginResolver;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24OriginResolver.Origin;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.math.BigDecimal;
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
class Cafe24PublishSchemaTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private HtmlImageExtractor imageExtractor;
	@Mock
	private Cafe24CategoryResolver categoryResolver;
	@Mock
	private Cafe24BrandCodeResolver brandCodeResolver;
	@Mock
	private Cafe24OriginResolver originResolver;

	private Cafe24MarketClient client;

	private static final String OK_RESPONSE = "{\"product\":{\"product_no\":\"999\",\"product_code\":\"P000000AB\"}}";

	@BeforeEach
	void setUp() {
		client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor,
			categoryResolver, brandCodeResolver, originResolver);
	}

	private Product product(BigDecimal weight) {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("19889"), "비타민D3 K2",
			"Vitamin D3 K2", "California Gold Nutrition", "미국",
			weight, new BigDecimal("180"), MeasureUnit.EA,
			List.of("https://src/1.jpg"), List.of("https://cdn/1.jpg"),
			"<div>본문</div>", "보충제", true, 1, new BigDecimal("20"), VendorType.IHB, null);
		return Product.create("250726IHB001", command);
	}

	private MarketPublishContext context() {
		return new MarketPublishContext("77", "건강기능식품", new BigDecimal("25000"), List.of(),
			Map.of(), Map.of());
	}

	private MarketPublishContext contextWithOrigin(String origin) {
		return new MarketPublishContext("77", "건강기능식품", new BigDecimal("25000"), List.of(),
			Map.of(), Map.of("originPlace", origin));
	}

	private void stubCreateAndImageUpload() {
		when(cafe24RestClient.post(eq("/admin/products"), any())).thenReturn(OK_RESPONSE);
		when(cafe24RestClient.getExternalImageBytes(any())).thenReturn(new byte[] {1, 2, 3});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturedRequest() {
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(cafe24RestClient).post(eq("/admin/products"), captor.capture());
		return (Map<String, Object>)captor.getValue().get("request");
	}

	@Test
	@DisplayName("D-295①: 등록 페이로드는 자유 텍스트 brand 대신 리졸버가 준 brand_code 를 보낸다")
	void sendsBrandCodeInsteadOfFreeTextBrand() {
		stubCreateAndImageUpload();
		when(brandCodeResolver.resolve("California Gold Nutrition")).thenReturn("B000000A");

		client.publish(product(new BigDecimal("0.30")), context());

		Map<String, Object> request = capturedRequest();
		assertThat(request.get("brand_code")).isEqualTo("B000000A");
		assertThat(request).doesNotContainKey("brand");
	}

	@Test
	@DisplayName("D-295①: 브랜드 코드 해석에 실패해도 등록 자체는 브랜드 없이 진행한다")
	void publishesWithoutBrandWhenResolverFails() {
		stubCreateAndImageUpload();
		when(brandCodeResolver.resolve("California Gold Nutrition"))
			.thenThrow(new IllegalStateException("[카페24] 브랜드 조회 실패"));

		assertThatCode(() -> client.publish(product(new BigDecimal("0.30")), context()))
			.doesNotThrowAnyException();

		Map<String, Object> request = capturedRequest();
		assertThat(request).doesNotContainKey("brand_code");
		assertThat(request).doesNotContainKey("brand");
	}

	@Test
	@DisplayName("D-295③: 등록 페이로드는 상품 중량을 product_weight(kg)로 담는다")
	void sendsProductWeightInKilograms() {
		stubCreateAndImageUpload();

		client.publish(product(new BigDecimal("0.30")), context());

		assertThat(capturedRequest().get("product_weight")).isEqualTo("0.30");
	}

	@Test
	@DisplayName("D-295③: 중량이 0이면 product_weight 를 아예 보내지 않는다")
	void omitsProductWeightWhenMissing() {
		stubCreateAndImageUpload();

		client.publish(product(BigDecimal.ZERO), context());

		assertThat(capturedRequest()).doesNotContainKey("product_weight");
	}

	@Test
	@DisplayName("D-295③: 등록 성공 후 검색키워드를 상품 태그로 등록한다")
	void registersSearchKeywordsAsProductTags() {
		stubCreateAndImageUpload();

		client.publish(product(new BigDecimal("0.30")), context());

		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(cafe24RestClient).post(eq("/admin/products/999/tags"), captor.capture());
		@SuppressWarnings("unchecked")
		Map<String, Object> request = (Map<String, Object>)captor.getValue().get("request");
		assertThat(request.get("tags"))
			.isEqualTo(List.of("California Gold Nutrition", "비타민D3 K2", "Vitamin D3 K2"));
	}

	@Test
	@DisplayName("D-295③: 태그 등록이 실패해도 상품 등록 결과는 유지된다")
	void tagFailureDoesNotBreakPublish() {
		stubCreateAndImageUpload();
		when(cafe24RestClient.post(startsWith("/admin/products/999/tags"), any()))
			.thenThrow(new RuntimeException("태그 등록 실패"));

		Map<String, String> identifiers = client.publish(product(new BigDecimal("0.30")), context());

		assertThat(identifiers).containsEntry("product_no", "999");
	}

	@Test
	@DisplayName("D-295③: 검색키워드가 없으면 태그 API 를 호출하지 않는다")
	void skipsTagCallWhenNoKeywords() {
		stubCreateAndImageUpload();
		Product product = product(new BigDecimal("0.30"));
		product.update(ProductUpdateCommand.builder().searchKeywords("  ").build());

		client.publish(product, context());

		verify(cafe24RestClient, never()).post(startsWith("/admin/products/999/tags"), any());
	}

	@Test
	@DisplayName("D-295②: 원산지 목록에서 해석되면 구분+원산지번호로 보내고 기타정보는 보내지 않는다")
	void sendsResolvedOriginClassificationAndPlaceNo() {
		stubCreateAndImageUpload();
		when(originResolver.resolve("미국")).thenReturn(new Origin("T", 1799, null));

		client.publish(product(new BigDecimal("0.30")), contextWithOrigin("미국"));

		Map<String, Object> request = capturedRequest();
		assertThat(request.get("origin_classification")).isEqualTo("T");
		assertThat(request.get("origin_place_no")).isEqualTo(1799);
		assertThat(request).doesNotContainKey("origin_place_value");
	}

	@Test
	@DisplayName("D-295②: 기타 원산지는 구분 E + 1800 + 기타정보 세 필드를 함께 보낸다")
	void sendsOtherOriginAsCompleteFieldSet() {
		stubCreateAndImageUpload();
		when(originResolver.resolve("상세설명 참조"))
			.thenReturn(new Origin("E", 1800, "상세설명 참조"));

		client.publish(product(new BigDecimal("0.30")), contextWithOrigin("상세설명 참조"));

		Map<String, Object> request = capturedRequest();
		assertThat(request.get("origin_classification")).isEqualTo("E");
		assertThat(request.get("origin_place_no")).isEqualTo(1800);
		assertThat(request.get("origin_place_value")).isEqualTo("상세설명 참조");
	}

	@Test
	@DisplayName("D-295②: 원산지 정보가 없으면 원산지 필드를 하나도 보내지 않는다")
	void omitsOriginFieldsWhenContextHasNoOrigin() {
		stubCreateAndImageUpload();

		client.publish(product(new BigDecimal("0.30")), context());

		Map<String, Object> request = capturedRequest();
		assertThat(request).doesNotContainKeys("origin_classification", "origin_place_no",
			"origin_place_value");
	}
}
