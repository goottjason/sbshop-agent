package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
class SmartstoreNoticeBackfillTest {

	@Mock
	private SmartstoreRestClient restClient;

	private SmartstoreMarketClient client;

	private static final String PATH = "/v2/products/origin-products/5583740567";

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(null, null, null, null, restClient, new ObjectMapper());
	}

	private Product product() {
		Product p = mock(Product.class);
		lenient().when(p.getProductSpec())
			.thenReturn(ProductSpec.builder().barcode("9506000113715").build());
		return p;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> captureNoticeBlock() {
		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq(PATH), body.capture());
		Map<String, Object> origin = (Map<String, Object>)body.getValue().get("originProduct");
		Map<String, Object> attr = (Map<String, Object>)origin.get("detailAttribute");
		Map<String, Object> notice = (Map<String, Object>)attr.get("productInfoProvidedNotice");
		return (Map<String, Object>)notice.get("generalFood");
	}

	@Test
	@DisplayName("소비기한이 없는 옛 고시정보는 유통기한 값으로 채워 PUT 이 거부되지 않게 한다")
	void backfillsConsumptionDateFromExpiration() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"detailAttribute\":{"
			+ "\"productInfoProvidedNotice\":{\"productInfoProvidedNoticeType\":\"GENERAL_FOOD\","
			+ "\"generalFood\":{\"expirationDateText\":\"상품 상세설명 참조\","
			+ "\"packDateText\":\"상품 상세설명 참조\"}}}}}");

		client.syncBarcode(product(), "5583740567", new HashMap<>());

		assertThat(captureNoticeBlock()).containsEntry("consumptionDate", "상품 상세설명 참조");
	}

	@Test
	@DisplayName("유통기한도 없으면 상세설명 참조로 채운다")
	void backfillsWithSeeDetailWhenNoExpiration() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"detailAttribute\":{"
			+ "\"productInfoProvidedNotice\":{\"productInfoProvidedNoticeType\":\"GENERAL_FOOD\","
			+ "\"generalFood\":{\"packDateText\":\"x\"}}}}}");

		client.syncBarcode(product(), "5583740567", new HashMap<>());

		assertThat(captureNoticeBlock()).containsEntry("consumptionDate", "상세설명 참조");
	}

	@Test
	@DisplayName("이미 소비기한이 있으면 덮어쓰지 않는다")
	void keepsExistingConsumptionDate() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"detailAttribute\":{"
			+ "\"productInfoProvidedNotice\":{\"productInfoProvidedNoticeType\":\"GENERAL_FOOD\","
			+ "\"generalFood\":{\"consumptionDate\":\"2027-01-01\"}}}}}");

		client.syncBarcode(product(), "5583740567", new HashMap<>());

		assertThat(captureNoticeBlock()).containsEntry("consumptionDate", "2027-01-01");
	}
}
