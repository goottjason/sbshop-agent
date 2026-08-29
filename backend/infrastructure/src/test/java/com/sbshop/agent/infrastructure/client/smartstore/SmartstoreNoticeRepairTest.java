package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartstoreNoticeRepairTest {

	@Mock
	private SmartstoreRestClient restClient;

	private SmartstoreMarketClient client;

	private static final String PATH = "/v2/products/origin-products/5583740567";

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(null, null, null, null, restClient, new ObjectMapper());
	}

	private com.sbshop.agent.core.domain.product.Product product(java.math.BigDecimal capacity,
		com.sbshop.agent.core.domain.product.enums.MeasureUnit unit) {
		com.sbshop.agent.core.domain.product.Product p = org.mockito.Mockito
			.mock(com.sbshop.agent.core.domain.product.Product.class);
		org.mockito.Mockito.lenient().when(p.getProductSpec())
			.thenReturn(com.sbshop.agent.core.domain.product.vo.ProductSpec.builder()
				.capacity(capacity).measureUnit(unit).build());
		return p;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> captureUnitCapacity() {
		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq(PATH), body.capture());
		Map<String, Object> origin = (Map<String, Object>)body.getValue().get("originProduct");
		Map<String, Object> attr = (Map<String, Object>)origin.get("detailAttribute");
		return (Map<String, Object>)attr.get("unitCapacity");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> captureBlock() {
		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(restClient).put(eq(PATH), body.capture());
		Map<String, Object> origin = (Map<String, Object>)body.getValue().get("originProduct");
		Map<String, Object> attr = (Map<String, Object>)origin.get("detailAttribute");
		Map<String, Object> notice = (Map<String, Object>)attr.get("productInfoProvidedNotice");
		return (Map<String, Object>)notice.get("generalFood");
	}

	@Test
	@DisplayName("바코드 없이도 소비기한만 보정한다 — 바코드 없는 상품도 수정 차단을 풀어야 한다")
	void repairsWithoutBarcode() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"detailAttribute\":{"
			+ "\"productInfoProvidedNotice\":{\"productInfoProvidedNoticeType\":\"GENERAL_FOOD\","
			+ "\"generalFood\":{\"expirationDateText\":\"상품 상세설명 참조\"}}}}}");

		boolean repaired = client.repairProductNotice(product(null, null), "5583740567");

		assertThat(repaired).isTrue();
		assertThat(captureBlock()).containsEntry("consumptionDateText", "상품 상세설명 참조");
	}

	@Test
	@DisplayName("이미 소비기한이 있으면 PUT 을 아예 보내지 않는다 — 불필요한 마켓 쓰기를 하지 않는다")
	void skipsPutWhenAlreadyPresent() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"detailAttribute\":{"
			+ "\"productInfoProvidedNotice\":{\"productInfoProvidedNoticeType\":\"GENERAL_FOOD\","
			+ "\"generalFood\":{\"consumptionDateText\":\"제품 표기일 참조\"}}}}}");

		boolean repaired = client.repairProductNotice(product(null, null), "5583740567");

		assertThat(repaired).isFalse();
		verify(restClient, never()).put(any(), any());
	}

	@Test
	@DisplayName("고시정보 블록이 없으면 건드리지 않는다")
	void skipsWhenNoNoticeBlock() {
		when(restClient.get(PATH))
			.thenReturn("{\"originProduct\":{\"detailAttribute\":{}}}");

		assertThat(client.repairProductNotice(product(null, null), "5583740567")).isFalse();
		verify(restClient, never()).put(any(), any());
	}

	@Test
	@DisplayName("unitPriceYn 이 비어 있으면 채운다 — 가격표시제 대상 품목은 이게 없으면 PUT 이 400 이다")
	void backfillsUnitPriceYnWhenMissing() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"detailAttribute\":{"
			+ "\"unitCapacity\":{},"
			+ "\"productInfoProvidedNotice\":{\"generalFood\":{\"expirationDateText\":\"참조\"}}}}}");

		boolean repaired = client.repairProductNotice(
			product(new java.math.BigDecimal("1"),
				com.sbshop.agent.core.domain.product.enums.MeasureUnit.L), "5583740567");

		assertThat(repaired).isTrue();
		assertThat(captureUnitCapacity()).containsEntry("unitPriceYn", true);
		assertThat(captureUnitCapacity()).containsEntry("indicationUnit", "L");
	}

	@Test
	@DisplayName("용량 단위를 못 정하면 unitPriceYn=false 로 선택만 해둔다 — 미선택 상태를 남기지 않는다")
	void setsFalseWhenUnitUnknown() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"detailAttribute\":{"
			+ "\"unitCapacity\":{},"
			+ "\"productInfoProvidedNotice\":{\"generalFood\":{\"expirationDateText\":\"참조\"}}}}}");

		client.repairProductNotice(product(new java.math.BigDecimal("16"),
			com.sbshop.agent.core.domain.product.enums.MeasureUnit.OZ), "5583740567");

		assertThat(captureUnitCapacity()).containsEntry("unitPriceYn", false);
	}

	@Test
	@DisplayName("이미 unitPriceYn 이 있으면 덮어쓰지 않는다 — 라이브 단위가격 설정을 보존한다")
	void keepsExistingUnitPriceYn() {
		when(restClient.get(PATH)).thenReturn("{\"originProduct\":{\"detailAttribute\":{"
			+ "\"unitCapacity\":{\"unitPriceYn\":true,\"indicationUnit\":\"g\"},"
			+ "\"productInfoProvidedNotice\":{\"generalFood\":{\"expirationDateText\":\"참조\"}}}}}");

		client.repairProductNotice(product(new java.math.BigDecimal("1"),
			com.sbshop.agent.core.domain.product.enums.MeasureUnit.L), "5583740567");

		assertThat(captureUnitCapacity()).containsEntry("indicationUnit", "g");
	}
}
