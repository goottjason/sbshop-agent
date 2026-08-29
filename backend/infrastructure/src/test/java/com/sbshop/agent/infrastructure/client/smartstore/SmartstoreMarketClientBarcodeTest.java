package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientBarcodeTest {

	@Mock
	private SmartstoreRestClient restClient;

	private SmartstoreMarketClient client;

	private static final String PATH = "/v2/products/origin-products/5219903157";

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(null, null, null, null, restClient, new ObjectMapper());
	}

	private Product product(String barcode) {
		Product p = mock(Product.class);
		lenient().when(p.getProductSpec()).thenReturn(ProductSpec.builder().barcode(barcode).build());
		return p;
	}

	private void stubGet() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"name\":\"상품\","
			+ "\"images\":{\"representativeImage\":{\"url\":\"http://img/1.jpg\"}},"
			+ "\"detailAttribute\":{\"sellerCodeInfo\":{\"sellerManagementCode\":\"201126IHB018\"}}}}");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> captureSellerCodeInfo() {
		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq(PATH), body.capture());
		Map<String, Object> origin = (Map<String, Object>)body.getValue().get("originProduct");
		Map<String, Object> attr = (Map<String, Object>)origin.get("detailAttribute");
		return (Map<String, Object>)attr.get("sellerCodeInfo");
	}

	@Test
	@DisplayName("신선 GET 페이로드의 sellerBarcode 만 덮어써 PUT 한다 — 이미지를 다시 올리지 않는다")
	void overwritesOnlySellerBarcode() {
		stubGet();

		client.syncBarcode(product("9400501001116"), "5219903157", new HashMap<>());

		assertThat(captureSellerCodeInfo()).containsEntry("sellerBarcode", "9400501001116");
		assertThat(captureSellerCodeInfo()).containsEntry("sellerManagementCode", "201126IHB018");
	}

	@Test
	@DisplayName("sellerCodeInfo 가 없어도 만들어서 싣는다")
	void createsSellerCodeInfoWhenAbsent() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"name\":\"상품\",\"detailAttribute\":{}}}");

		client.syncBarcode(product("9400501001116"), "5219903157", new HashMap<>());

		assertThat(captureSellerCodeInfo()).containsEntry("sellerBarcode", "9400501001116");
	}

	@Test
	@DisplayName("바코드가 없으면 호출하지 않는다")
	void skipsWhenNoBarcode() {
		client.syncBarcode(product(null), "5219903157", new HashMap<>());

		verify(restClient, never()).put(any(), any());
	}
}
