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
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.util.ArrayList;
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
class Cafe24MarketClientBarcodeTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private HtmlImageExtractor imageExtractor;
	@Mock
	private Cafe24CategoryResolver categoryResolver;

	private Cafe24MarketClient client;

	@BeforeEach
	void setUp() {
		client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor,
			categoryResolver);
	}

	private Product product(String barcode) {
		Product p = mock(Product.class);
		lenient().when(p.getProductSpec()).thenReturn(ProductSpec.builder().barcode(barcode).build());
		return p;
	}

	private Map<String, Object> rawDataWithVariant(String variantCode) {
		Map<String, Object> variant = new HashMap<>();
		variant.put("variant_code", variantCode);
		List<Map<String, Object>> variants = new ArrayList<>();
		variants.add(variant);
		Map<String, Object> raw = new HashMap<>();
		raw.put("variants", variants);
		return raw;
	}

	@Test
	@DisplayName("variant_code 를 라이브 GET 으로 조달한다 — 저장된 rawData 에는 variants 가 없다")
	void fetchesVariantCodeLive() {
		when(cafe24RestClient.get("/admin/products/22016/variants"))
			.thenReturn("{\"variants\":[{\"variant_code\":\"P000BDTF000A\"}]}");

		client.syncBarcode(product("9400501001116"), "22016", new HashMap<>());

		verify(cafe24RestClient).put(eq("/admin/products/22016/variants/P000BDTF000A"), any());
	}

	@Test
	@DisplayName("바코드를 variants 의 gtin 으로 PUT 한다 — 카페24는 상품 레벨에 바코드 필드가 없다")
	void putsGtinOnVariant() {
		when(cafe24RestClient.get("/admin/products/22016/variants"))
			.thenReturn("{\"variants\":[{\"variant_code\":\"P000BDTF000A\"}]}");

		client.syncBarcode(product("9400501001116"), "22016", rawDataWithVariant("P000BDTF000A"));

		ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
		verify(cafe24RestClient).put(eq("/admin/products/22016/variants/P000BDTF000A"), body.capture());

		@SuppressWarnings("unchecked") Map<String, Object> request = (Map<String, Object>)body.getValue()
			.get("request");
		assertThat(request).containsEntry("gtin", "9400501001116");
		assertThat(request).containsEntry("shop_no", 1);
	}

	@Test
	@DisplayName("바코드가 없으면 아무것도 전송하지 않는다 — 빈 값 전송은 기존 gtin 을 지운다")
	void skipsWhenNoBarcode() {
		client.syncBarcode(product(null), "22016", rawDataWithVariant("P000BDTF000A"));

		verify(cafe24RestClient, never()).put(any(), any());
	}

	@Test
	@DisplayName("variant_code 를 못 찾으면 조용히 넘어가지 않고 실패로 알린다")
	void failsWhenVariantMissing() {
		when(cafe24RestClient.get("/admin/products/22016/variants")).thenReturn("{\"variants\":[]}");

		assertThatThrownBy(() -> client.syncBarcode(product("9400501001116"), "22016", new HashMap<>()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("variant");

		verify(cafe24RestClient, never()).put(any(), any());
	}

	@Test
	@DisplayName("전송 후 로컬 rawData 의 gtin 도 갱신해 다음 비교가 어긋나지 않게 한다")
	void updatesLocalRawData() {
		when(cafe24RestClient.get("/admin/products/22016/variants"))
			.thenReturn("{\"variants\":[{\"variant_code\":\"P000BDTF000A\"}]}");
		Map<String, Object> raw = rawDataWithVariant("P000BDTF000A");

		client.syncBarcode(product("9400501001116"), "22016", raw);

		@SuppressWarnings("unchecked") List<Map<String, Object>> variants = (List<Map<String, Object>>)raw
			.get("variants");
		assertThat(variants.get(0)).containsEntry("gtin", "9400501001116");
	}
}
