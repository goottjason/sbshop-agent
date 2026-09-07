package com.sbshop.agent.infrastructure.client.cafe24.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.*;
import org.junit.jupiter.api.*;

class Cafe24ReviewedFieldsTest {
	final ObjectMapper mapper = new ObjectMapper();
	final Cafe24RestClient rest = mock(Cafe24RestClient.class);
	final Product product = mock(Product.class);
	final Cafe24ReviewedFields fields = new Cafe24ReviewedFields(rest, mapper);
	ObjectNode current;
	final String path = "/admin/products/123";

	@BeforeEach void setup(){
        when(rest.accountReference()).thenReturn("mall:shop1");when(rest.put(any(),any())).thenReturn("{}");
        when(product.getSbCode()).thenReturn("SB-123");when(product.getProductName()).thenReturn("새 이름");
        when(product.getDetailHtml()).thenReturn("<p>새 상세</p>");
        current=mapper.createObjectNode().put("shop_no",1).put("product_no",123).put("product_code","P000000A")
            .put("custom_product_code","SB-123").put("market_sync","F").put("selling","T")
            .put("product_name","기존").put("description","기존상세").put("price","12300.00");publish();
    }

	void publish(){when(rest.get(path+"?shop_no=1")).thenReturn(mapper.createObjectNode().set("product",current).toString());}

	@Test
	void deltaContainsOnlyReviewedFieldsAndTopLevelShop() {
		var prepared = fields.prepare(product, "123", null, Set.of("name", "detailHtml"));
		var guard = mock(Runnable.class);
		fields.write("123", null, "SB-123", prepared, guard);
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).put(path,
			Map.of("shop_no", 1, "request", Map.of("product_name", "새 이름", "description", "<p>새 상세</p>")));
	}

	@Test
	void wrongShopSbOrUnprovenMarketPlusPropagationCannotWrite() {
		current.put("shop_no", 2);
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", null, Set.of("name"))).hasMessageContaining("쇼핑몰");
		current.put("shop_no", 1).put("custom_product_code", "other");
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", null, Set.of("name"))).hasMessageContaining("SB코드");
		current.put("custom_product_code", "SB-123").put("market_sync", "T");
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", null, Set.of("name"))).hasMessageContaining("마켓플러스");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void readActualValuesRatherThanReturningSavedTargetsAndMissingValuesFail() {
		var observed = fields.read("123", null, "SB-123", Set.of("name", "detailHtml"));
		assertThat(observed.values()).containsEntry("name", "기존").containsEntry("detailHtml", "기존상세");
		current.remove("description");
		publish();
		assertThatThrownBy(() -> fields.read("123", null, "SB-123", Set.of("detailHtml")))
			.hasMessageContaining("실제 필드 값");
	}

	@Test
	void identicalValuesDoNotWriteAndGuardChangingAccountPreventsMutation() {
		var prepared = fields.prepare(product, "123", null, Set.of("name"));
		current.put("product_name", "새 이름");
		publish();
		fields.write("123", null, "SB-123", prepared, () -> {});
		verify(rest, never()).put(any(), any());
		current.put("product_name", "다시 변경");
		publish();
		assertThatThrownBy(() -> fields.write("123", null, "SB-123", prepared,
			() -> when(rest.accountReference()).thenReturn("other"))).hasMessageContaining("계정");
		verify(rest, never()).put(any(), any());
	}

	@Test void brandPreparationRequiresOneExistingExactCodeAndNeverCreatesABrand() {
        when(product.getBrand()).thenReturn("Brand");
        when(rest.get("/admin/brands?shop_no=1&brand_name=Brand")).thenReturn("{\"brands\":[{\"brand_name\":\"Brand\",\"brand_code\":\"B000000A\"}]}");
        assertThat(fields.prepare(product,"123",null,Set.of("brand")).expectedValues()).containsEntry("brand","B000000A");
        when(rest.get("/admin/brands?shop_no=1&brand_name=Brand")).thenReturn("{\"brands\":[]}");
        assertThatThrownBy(()->fields.prepare(product,"123",null,Set.of("brand"))).hasMessageContaining("하나로 확인되지");
        verify(rest,never()).post(any(),any());
    }
}
