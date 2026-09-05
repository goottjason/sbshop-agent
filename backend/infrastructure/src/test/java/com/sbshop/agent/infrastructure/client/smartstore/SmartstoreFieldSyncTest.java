package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.SourcingInfo;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartstoreFieldSyncTest {

	@Mock
	private SmartstoreRestClient restClient;

	private SmartstoreMarketClient client;

	private static final String PATH = "/v2/products/origin-products/5219903157";

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(null, null, null, null, restClient, new ObjectMapper());
	}

	private Product product(String name, String brand, String manufacturer) {
		Product p = mock(Product.class);
		lenient().when(p.getProductName()).thenReturn(name);
		lenient().when(p.getBrand()).thenReturn(brand);
		if (manufacturer != null) {
			lenient().when(p.getSourcingInfo())
				.thenReturn(SourcingInfo.builder().manufacturer(manufacturer).build());
		}
		return p;
	}

	private void stubGet() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"name\":\"기존이름\","
			+ "\"statusType\":\"SALE\","
			+ "\"detailAttribute\":{\"naverShoppingSearchInfo\":{"
			+ "\"brandName\":\"기존브랜드\",\"manufacturerName\":\"기존제조사\"}}}}");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> captureOriginProduct() {
		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq(PATH), body.capture());
		return (Map<String, Object>)body.getValue().get("originProduct");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> captureSearchInfo() {
		Map<String, Object> origin = captureOriginProduct();
		Map<String, Object> attr = (Map<String, Object>)origin.get("detailAttribute");
		return (Map<String, Object>)attr.get("naverShoppingSearchInfo");
	}

	@Test
	@DisplayName("D-294: 브랜드만 치환하고 상품명·제조사는 그대로 둔다")
	void overwritesOnlyBrand() {
		stubGet();

		client.syncProductFields(product(null, "새브랜드", null), "5219903157", new HashMap<>(),
			Set.of(MarketEditField.BRAND));

		assertThat(captureSearchInfo()).containsEntry("brandName", "새브랜드");
		assertThat(captureSearchInfo()).containsEntry("manufacturerName", "기존제조사");
		assertThat(captureOriginProduct()).containsEntry("name", "기존이름");
	}

	@Test
	@DisplayName("D-294: 상품명·브랜드·제조사 세 필드를 동시에 치환한다")
	void updatesAllThreeFieldsAtOnce() {
		stubGet();

		client.syncProductFields(product("새이름", "새브랜드", "새제조사"), "5219903157", new HashMap<>(),
			Set.of(MarketEditField.PRODUCT_NAME, MarketEditField.BRAND, MarketEditField.MANUFACTURER));

		assertThat(captureOriginProduct()).containsEntry("name", "새이름");
		assertThat(captureSearchInfo()).containsEntry("brandName", "새브랜드");
		assertThat(captureSearchInfo()).containsEntry("manufacturerName", "새제조사");
	}

	@Test
	@DisplayName("D-294: 값이 비어있는 필드는 마켓 값을 빈 값으로 덮지 않는다")
	void skipsNullOrBlankSourceValue() {
		stubGet();

		client.syncProductFields(product(null, null, "새제조사"), "5219903157", new HashMap<>(),
			Set.of(MarketEditField.BRAND, MarketEditField.MANUFACTURER));

		assertThat(captureSearchInfo()).containsEntry("brandName", "기존브랜드");
		assertThat(captureSearchInfo()).containsEntry("manufacturerName", "새제조사");
	}

	@Test
	@DisplayName("D-294: statusType 이 비어있는 옛 상품도 SALE 로 보정해 PUT 이 거부되지 않게 한다")
	void backfillsStatusTypeWhenMissing() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"name\":\"기존이름\","
			+ "\"detailAttribute\":{}}}");

		client.syncProductFields(product("새이름", null, null), "5219903157", new HashMap<>(),
			Set.of(MarketEditField.PRODUCT_NAME));

		assertThat(captureOriginProduct()).containsEntry("statusType", "SALE");
	}

	@Test
	@DisplayName("D-294: PUT 실패는 예외로 그대로 전파한다")
	void propagatesExceptionOnPutFailure() {
		stubGet();
		when(restClient.put(eq(PATH), org.mockito.ArgumentMatchers.any()))
			.thenThrow(new RuntimeException("마켓 오류"));

		assertThatThrownBy(() -> client.syncProductFields(product("새이름", null, null), "5219903157",
			new HashMap<>(), Set.of(MarketEditField.PRODUCT_NAME)))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("마켓 오류");
	}
}
