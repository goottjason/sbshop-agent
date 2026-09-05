package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.math.BigDecimal;
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
class Cafe24MarketClientSupplyPriceTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private HtmlImageExtractor imageExtractor;
	@Mock
	private Cafe24CategoryResolver categoryResolver;

	private Cafe24MarketClient client;

	private static final String OK_RESPONSE = "{\"product\":{\"product_no\":\"999\",\"product_code\":\"P000000AB\"}}";

	@BeforeEach
	void setUp() {
		client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor, categoryResolver, null);
	}

	private Product product(BigDecimal costPrice) {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", costPrice, "비타민D3 K2",
			"Vitamin D3 K2", "California Gold Nutrition", "미국",
			new BigDecimal("60"), new BigDecimal("180"), MeasureUnit.EA,
			List.of("https://src/1.jpg"), List.of("https://cdn/1.jpg"),
			"<div>본문</div>", "보충제", true, 1, new BigDecimal("20"), VendorType.IHB, null);
		return Product.create("250726IHB001", command);
	}

	private MarketPublishContext context(BigDecimal salePrice) {
		return new MarketPublishContext("77", "건강기능식품", salePrice, List.of(), Map.of(), Map.of());
	}

	private void stubCreateAndImageUpload() {
		when(cafe24RestClient.post(eq("/admin/products"), any())).thenReturn(OK_RESPONSE);
		when(cafe24RestClient.getExternalImageBytes(any())).thenReturn(new byte[] {1, 2, 3});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> capturedRequest() {
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(cafe24RestClient).post(eq("/admin/products"), captor.capture());
		return (Map<String, Object>)captor.getValue().get("request");
	}

	@Test
	@DisplayName("등록 페이로드는 supply_price를 원가의 정수 문자열로 담는다")
	void sendsSupplyPriceAsIntegerStringFromCostPrice() {
		stubCreateAndImageUpload();

		client.publish(product(new BigDecimal("19889.50")), context(null));

		assertThat(capturedRequest().get("supply_price")).isEqualTo("19889");
	}

	@Test
	@DisplayName("원가가 없으면 supply_price는 판매가 정수 문자열로 폴백한다")
	void fallsBackToSalePriceWhenCostPriceMissing() {
		stubCreateAndImageUpload();
		Product product = product(new BigDecimal("19889.50"));
		product.updateCostPrice(null);

		client.publish(product, context(new BigDecimal("25000")));

		assertThat(capturedRequest().get("supply_price")).isEqualTo("25000");
	}

	@Test
	@DisplayName("원가가 0 이하이면 supply_price는 판매가 정수 문자열로 폴백한다")
	void fallsBackToSalePriceWhenCostPriceNotPositive() {
		stubCreateAndImageUpload();

		client.publish(product(BigDecimal.ZERO), context(new BigDecimal("25000")));

		assertThat(capturedRequest().get("supply_price")).isEqualTo("25000");
	}

	@Test
	@DisplayName("supply_price 추가가 기존 price 필드를 바꾸지 않는다")
	void keepsSalePriceFieldUnchanged() {
		stubCreateAndImageUpload();

		client.publish(product(new BigDecimal("19889.50")), context(new BigDecimal("25000")));

		assertThat(capturedRequest().get("price")).isEqualTo("25000");
	}
}
