package com.sbshop.agent.infrastructure.client.cafe24;

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
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24BrandCodeResolver;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
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
class Cafe24FieldSyncTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private HtmlImageExtractor imageExtractor;
	@Mock
	private Cafe24CategoryResolver categoryResolver;
	@Mock
	private Cafe24BrandCodeResolver brandCodeResolver;

	private Cafe24MarketClient client;

	@BeforeEach
	void setUp() {
		client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor,
			categoryResolver, brandCodeResolver, null);
	}

	private Product product(String name, String brand) {
		Product p = mock(Product.class);
		lenient().when(p.getProductName()).thenReturn(name);
		lenient().when(p.getBrand()).thenReturn(brand);
		return p;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturedRequest() {
		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(cafe24RestClient).put(eq("/admin/products/22016"), body.capture());
		return (Map<String, Object>)body.getValue().get("request");
	}

	@Test
	@DisplayName("D-294: 상품명만 요청하면 product_name 만 PUT 한다")
	void syncsProductNameOnly() {
		client.syncProductFields(product("새 상품명", null), "22016", new HashMap<>(),
			Set.of(MarketEditField.PRODUCT_NAME));

		Map<String, Object> request = capturedRequest();
		assertThat(request).containsEntry("product_name", "새 상품명");
		assertThat(request).doesNotContainKey("brand_code");
		assertThat(request).doesNotContainKey("manufacturer_code");
	}

	@Test
	@DisplayName("D-294: 브랜드 요청 시 브랜드 코드 리졸버가 돌려준 brand_code 를 전송한다")
	void syncsBrandUsingResolvedCode() {
		when(brandCodeResolver.resolve("아이허브")).thenReturn("B0000001");

		client.syncProductFields(product(null, "아이허브"), "22016", new HashMap<>(),
			Set.of(MarketEditField.BRAND));

		Map<String, Object> request = capturedRequest();
		assertThat(request).containsEntry("brand_code", "B0000001");
	}

	@Test
	@DisplayName("D-294: 브랜드가 비어 있으면 리졸버를 부르지 않고 brand_code 도 보내지 않는다")
	void skipsBlankBrand() {
		client.syncProductFields(product("상품명", null), "22016", new HashMap<>(),
			Set.of(MarketEditField.PRODUCT_NAME, MarketEditField.BRAND));

		verify(brandCodeResolver, never()).resolve(any());
		Map<String, Object> request = capturedRequest();
		assertThat(request).doesNotContainKey("brand_code");
	}

	@Test
	@DisplayName("D-294: MANUFACTURER 단독 요청은 미지원으로 던진다")
	void manufacturerAloneUnsupported() {
		assertThatThrownBy(() -> client.syncProductFields(product("이름", null), "22016",
			new HashMap<>(), Set.of(MarketEditField.MANUFACTURER)))
			.isInstanceOf(UnsupportedOperationException.class);

		verify(cafe24RestClient, never()).put(any(), any());
	}

	@Test
	@DisplayName("D-294: MANUFACTURER 가 섞여 있어도 지원 필드는 반영한다")
	void manufacturerMixedWithSupportedField() {
		client.syncProductFields(product("이름", null), "22016", new HashMap<>(),
			Set.of(MarketEditField.PRODUCT_NAME, MarketEditField.MANUFACTURER));

		Map<String, Object> request = capturedRequest();
		assertThat(request).containsEntry("product_name", "이름");
	}

	@Test
	@DisplayName("D-294: 브랜드 조회/등록 실패는 그대로 예외로 전파한다 — 조용히 스킵하지 않는다")
	void propagatesResolverFailure() {
		when(brandCodeResolver.resolve("아이허브")).thenThrow(new IllegalStateException("조회 실패"));

		assertThatThrownBy(() -> client.syncProductFields(product(null, "아이허브"), "22016",
			new HashMap<>(), Set.of(MarketEditField.BRAND)))
			.isInstanceOf(IllegalStateException.class);

		verify(cafe24RestClient, never()).put(any(), any());
	}
}
