package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.dto.CategoryMetaResult;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class CoupangDraftPublishNameTest {
	@ParameterizedTest
	@ValueSource(strings = {"쿠팡 검수 상품명 60캡슐 2개", " "})
	void reviewedMasterNameKeepsBundleOptionAndCommonProductUnchanged(String reviewedName) throws Exception {
		ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		CoupangRestClient rest = mock(CoupangRestClient.class);
		CoupangMetaService metadata = mock(CoupangMetaService.class);
		CoupangMarketClient client = new CoupangMarketClient(null, mapper, rest, null,
			null, null, null, metadata, new CoupangAttributeValueResolver());
		Product product = Product.create("261007IHB001", new ProductCreateCommand(
			"https://kr.iherb.com/pr/item/1", BigDecimal.TEN, "공통 상품명", "Product", "브랜드", "USA",
			BigDecimal.ONE, new BigDecimal("60"), MeasureUnit.CAPSULE, List.of(),
			List.of("https://cdn.example/a.jpg"), "상세", "supplements", true, 2,
			new BigDecimal("20"), VendorType.IHB, null));
		MarketPublishContext base = new MarketPublishContext("123", "건강", new BigDecimal("20000"),
			List.of("비타민"), Map.of(), Map.of());
		ObjectNode json = mapper.valueToTree(base);
		json.put("productName", reviewedName);
		when(metadata.getCategoryMeta(eq(123L), eq(product)))
			.thenReturn(new CategoryMetaResult(List.of(), List.of()));
		when(rest.requestWithBody(eq("POST"), any(), any())).thenReturn("{\"code\":\"SUCCESS\",\"data\":123}");

		client.publish(product, mapper.convertValue(json, MarketPublishContext.class));

		ArgumentCaptor<CoupangProductPayload> payload = ArgumentCaptor.forClass(CoupangProductPayload.class);
		verify(rest).requestWithBody(eq("POST"), any(), payload.capture());
		String expected = reviewedName.isBlank() ? "공통 상품명" : reviewedName;
		assertThat(payload.getValue().sellerProductName()).isEqualTo(expected);
		assertThat(payload.getValue().displayProductName()).isEqualTo(expected);
		assertThat(payload.getValue().items()).singleElement()
			.satisfies(item -> assertThat(item.itemName()).isEqualTo("2개"));
		assertThat(product.getBaseName()).isEqualTo("공통 상품명");
	}
}
