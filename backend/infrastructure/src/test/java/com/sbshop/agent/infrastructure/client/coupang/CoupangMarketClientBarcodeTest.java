package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
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
class CoupangMarketClientBarcodeTest {

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
	private static final String GET_PATH = BASE + "/11401410095";

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, new ObjectMapper(), restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService, new CoupangAttributeValueResolver());
	}

	private Product product(String barcode) {
		Product p = mock(Product.class);
		lenient().when(p.getProductSpec()).thenReturn(ProductSpec.builder().barcode(barcode).build());
		return p;
	}

	private void stubGet() {
		when(restClient.get(GET_PATH)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{"
			+ "\"sellerProductId\":11401410095,"
			+ "\"items\":[{\"itemName\":\"1개\",\"barcode\":\"\",\"emptyBarcode\":true,"
			+ "\"emptyBarcodeReason\":\"\"}]}}");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturePut() {
		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq(BASE), body.capture());
		return body.getValue();
	}

	@Test
	@DisplayName("신선 GET 페이로드의 바코드 3필드만 덮어써 PUT 한다 — 이미지·상세는 손대지 않는다")
	void overwritesOnlyBarcodeFields() {
		stubGet();
		when(restClient.put(eq(BASE), any())).thenReturn("{\"code\":\"SUCCESS\",\"data\":1}");

		client.syncBarcode(product("9400501001116"), "11401410095", new HashMap<>());

		@SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>)capturePut()
			.get("items");
		assertThat(items.get(0)).containsEntry("barcode", "9400501001116");
		assertThat(items.get(0)).containsEntry("emptyBarcode", false);
		assertThat(items.get(0)).containsEntry("itemName", "1개");
	}

	@Test
	@DisplayName("승인이 필요한 변경이라 requested=true 로 보낸다")
	void sendsRequestedTrue() {
		stubGet();
		when(restClient.put(eq(BASE), any())).thenReturn("{\"code\":\"SUCCESS\",\"data\":1}");

		client.syncBarcode(product("9400501001116"), "11401410095", new HashMap<>());

		assertThat(capturePut()).containsEntry("requested", true);
	}

	@Test
	@DisplayName("바코드가 없으면 호출하지 않는다")
	void skipsWhenNoBarcode() {
		client.syncBarcode(product(null), "11401410095", new HashMap<>());

		verify(restClient, never()).put(any(), any());
	}

	@Test
	@DisplayName("실패 봉투는 예외로 올린다 — 거짓 성공을 만들지 않는다")
	void strictEnvelope() {
		stubGet();
		when(restClient.put(eq(BASE), any()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"유효하지 않은 바코드\"}");

		assertThatThrownBy(
			() -> client.syncBarcode(product("9400501001116"), "11401410095", new HashMap<>()))
			.hasMessageContaining("유효하지 않은 바코드");
	}
}
