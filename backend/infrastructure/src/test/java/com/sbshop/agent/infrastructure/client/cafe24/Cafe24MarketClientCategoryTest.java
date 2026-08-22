package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24MarketClientCategoryTest {

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
		client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor, categoryResolver);
	}

	private Product product() {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("20000"), "비타민D3 K2",
			"Vitamin D3 K2", "California Gold Nutrition", "미국",
			new BigDecimal("60"), new BigDecimal("180"), MeasureUnit.EA,
			List.of("https://src/1.jpg"), List.of("https://cdn/1.jpg"),
			"<div>본문</div>", "보충제", true, 1, new BigDecimal("20"), VendorType.IHB);
		return Product.create("250726IHB001", command);
	}

	@Test
	@DisplayName("컨텍스트에 카테고리가 없어도 고신뢰(confident=true) 자동 해석이면 등록에 반영한다")
	void noCategoryInContext_confidentResolution_resolvesAutomatically() {
		when(categoryResolver.resolve(any(), any(), any()))
			.thenReturn(new MarketCategory("55", "건강기능식품 > 비타민", true));
		when(cafe24RestClient.post(eq("/admin/products"), any())).thenReturn(OK_RESPONSE);
		when(cafe24RestClient.getExternalImageBytes(any())).thenReturn(new byte[] {1, 2, 3});

		client.publish(product(), MarketPublishContext.empty());

		verify(categoryResolver).resolve(any(), any(), eq("California Gold Nutrition"));
	}

	@Test
	@DisplayName("자동 해석도 실패하면(분류 0개) 등록을 거부한다 — 유령 상품을 만들지 않는다")
	void resolverUnresolved_rejectsPublish() {
		when(categoryResolver.resolve(any(), any(), any())).thenReturn(MarketCategory.unresolved());

		assertThatThrownBy(() -> client.publish(product(), MarketPublishContext.empty()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("진열 분류");

		verify(cafe24RestClient, never()).post(eq("/admin/products"), any());
	}

	@Test
	@DisplayName("수정 라운드 1: 저신뢰(confident=false) 폴백은 '구했다'로 치지 않고 거부한다")
	void resolverLowConfidenceFallback_rejectsPublish() {
		when(categoryResolver.resolve(any(), any(), any()))
			.thenReturn(new MarketCategory("1", "전체상품 (자동 폴백)", false));

		assertThatThrownBy(() -> client.publish(product(), MarketPublishContext.empty()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("확신");

		verify(cafe24RestClient, never()).post(eq("/admin/products"), any());
	}

	@Test
	@DisplayName("컨텍스트에 카테고리가 이미 있으면 자동 해석을 건너뛴다(초안 등록 경로 회귀 방지)")
	void contextHasCategory_skipsResolver() {
		when(cafe24RestClient.post(eq("/admin/products"), any())).thenReturn(OK_RESPONSE);
		when(cafe24RestClient.getExternalImageBytes(any())).thenReturn(new byte[] {1, 2, 3});
		MarketPublishContext context =
			new MarketPublishContext("77", "건강기능식품", null, List.of(), Map.of(), Map.of());

		client.publish(product(), context);

		verify(categoryResolver, never()).resolve(any(), any(), any());
	}
}
