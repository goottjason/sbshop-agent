package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.math.BigDecimal;
import java.util.HashMap;
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
class SmartstoreMarketClientImagesTest {

	@Mock
	private SmartstoreRestClient restClient;

	private SmartstoreMarketClient client;

	private static final String ITEM_ID = "OP1";

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(
			null, null, null, null,
			restClient, new ObjectMapper());
	}

	private void stubGet(String json) {
        when(restClient.get(any())).thenReturn(json);
    }

	@Test
	@DisplayName("다중이미지: images.representativeImage.url==hostedImages[0], images.optionalImages==[{url}..], detailContent 세팅")
	void multipleImages_setsRepresentativeAndOptionalAndDetailContent() throws Exception {
		stubGet("{\"originProduct\":{\"images\":{\"representativeImage\":{\"url\":\"old\"}}}}");
		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

		client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of("u0.jpg", "u1.jpg", "u2.jpg"), "<html>");

		verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
		@SuppressWarnings("unchecked") Map<String, Object> originProduct = (Map<String, Object>)captor.getValue()
			.get("originProduct");
		@SuppressWarnings("unchecked") Map<String, Object> images = (Map<String, Object>)originProduct.get("images");
		@SuppressWarnings("unchecked") Map<String, Object> representativeImage = (Map<String, Object>)images
			.get("representativeImage");
		assertThat(representativeImage.get("url")).isEqualTo("u0.jpg");
		assertThat(images.get("optionalImages")).isEqualTo(List.of(Map.of("url", "u1.jpg"), Map.of("url", "u2.jpg")));
		assertThat(originProduct.get("detailContent")).isNotNull();
	}

	@Test
	@DisplayName("단일이미지: images.representativeImage.url 세팅, optionalImages 세팅 안 함")
	void singleImage_setsRepresentativeOnly_noOptionalImages() throws Exception {
		stubGet("{\"originProduct\":{\"images\":{\"representativeImage\":{\"url\":\"old\"}}}}");
		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

		client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of("u0.jpg"), null);

		verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
		@SuppressWarnings("unchecked") Map<String, Object> originProduct = (Map<String, Object>)captor.getValue()
			.get("originProduct");
		@SuppressWarnings("unchecked") Map<String, Object> images = (Map<String, Object>)originProduct.get("images");
		@SuppressWarnings("unchecked") Map<String, Object> representativeImage = (Map<String, Object>)images
			.get("representativeImage");
		assertThat(representativeImage.get("url")).isEqualTo("u0.jpg");
		assertThat(images.containsKey("optionalImages")).isFalse();
	}

	@Test
    @DisplayName("실패 표면화: GET 예외 시 예외가 호출자로 전파된다")
    void getFailure_propagatesException() {
        when(restClient.get(any())).thenThrow(new RuntimeException("네트워크 오류"));

        assertThatThrownBy(() ->
            client.syncImagesAndHtml(null, ITEM_ID, new HashMap<>(), List.of("u0.jpg"), null)
        ).isInstanceOf(RuntimeException.class)
         .hasMessageContaining("네트워크 오류");
    }

	private Product productWithSpec(BigDecimal capacity, MeasureUnit unit) {
		Product product = mock(Product.class);
		lenient().when(product.getProductSpec())
			.thenReturn(ProductSpec.builder().capacity(capacity).measureUnit(unit).build());
		return product;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturePutOriginProduct() {
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
		return (Map<String, Object>)captor.getValue().get("originProduct");
	}

	@Test
	@DisplayName("가격표시제 품목: 이미지/HTML 재게시 페이로드에 unitCapacity(unitPriceYn=true, 단위용량)가 포함된다")
	void unitPriceProduct_includesUnitCapacityInImagesPayload() throws Exception {
		stubGet("{\"originProduct\":{\"images\":{\"representativeImage\":{\"url\":\"old\"}}}}");

		client.syncImagesAndHtml(productWithSpec(new BigDecimal("28"), MeasureUnit.G),
			ITEM_ID, new HashMap<>(), List.of("u0.jpg"), "<html>");

		@SuppressWarnings("unchecked") Map<String, Object> detailAttribute = (Map<String, Object>)capturePutOriginProduct()
			.get("detailAttribute");
		@SuppressWarnings("unchecked") Map<String, Object> unitCapacity = (Map<String, Object>)detailAttribute
			.get("unitCapacity");
		assertThat(unitCapacity).isNotNull();
		assertThat(unitCapacity.get("unitPriceYn")).isEqualTo(true);
		assertThat(unitCapacity.get("totalCapacityValue")).isEqualTo(new BigDecimal("28"));
		assertThat(unitCapacity.get("unitCapacity")).isEqualTo(100);
		assertThat(unitCapacity.get("indicationUnit")).isEqualTo("g");
	}

	@Test
	@DisplayName("용량 스펙 없는 상품: unitPriceYn=false로 채워 전송한다")
	void productWithoutSpec_setsUnitPriceYnFalse() throws Exception {
		stubGet("{\"originProduct\":{\"images\":{\"representativeImage\":{\"url\":\"old\"}}}}");

		client.syncImagesAndHtml(productWithSpec(null, null),
			ITEM_ID, new HashMap<>(), List.of("u0.jpg"), null);

		@SuppressWarnings("unchecked") Map<String, Object> detailAttribute = (Map<String, Object>)capturePutOriginProduct()
			.get("detailAttribute");
		@SuppressWarnings("unchecked") Map<String, Object> unitCapacity = (Map<String, Object>)detailAttribute
			.get("unitCapacity");
		assertThat(unitCapacity).isNotNull();
		assertThat(unitCapacity.get("unitPriceYn")).isEqualTo(false);
		assertThat(unitCapacity.containsKey("totalCapacityValue")).isFalse();
	}

	@Test
	@DisplayName("보존 가드: customsTaxType과 기존 detailAttribute의 다른 키가 유지된다")
	void preservesCustomsTaxTypeAndOtherDetailAttributeKeys() throws Exception {
		stubGet("{\"originProduct\":{\"images\":{\"representativeImage\":{\"url\":\"old\"}},"
			+ "\"detailAttribute\":{\"minorPurchasable\":true,\"afterServiceInfo\":{\"afterServiceTelephoneNumber\":\"010\"}}}}");

		client.syncImagesAndHtml(productWithSpec(new BigDecimal("28"), MeasureUnit.G),
			ITEM_ID, new HashMap<>(), List.of("u0.jpg"), "<html>");

		Map<String, Object> originProduct = capturePutOriginProduct();
		@SuppressWarnings("unchecked") Map<String, Object> detailAttribute = (Map<String, Object>)originProduct
			.get("detailAttribute");
		assertThat(detailAttribute.get("customsTaxType")).isEqualTo("INCLUDED");
		assertThat(detailAttribute.get("minorPurchasable")).isEqualTo(true);
		assertThat(detailAttribute.get("afterServiceInfo")).isNotNull();
		assertThat(detailAttribute).containsKey("unitCapacity");
		assertThat(originProduct.get("detailContent")).isEqualTo("<html>");
	}
}
