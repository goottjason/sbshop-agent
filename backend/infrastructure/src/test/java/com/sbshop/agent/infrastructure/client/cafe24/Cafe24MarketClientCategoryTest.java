package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 결함 A: 카테고리 없는 카페24 등록 거부.
 *
 * <p>종전 구현은 진열 분류가 없으면 {@code log.warn}만 남기고 그대로 등록해, 어느 진열에도
 * 걸리지 않는 유령 상품이 조용히 생겼다. 이제는 (1) {@link Cafe24CategoryResolver}로 자동 해석을
 * 먼저 시도하고, (2) 그래도 못 구하면(분류 목록 자체가 없음) 등록을 거부해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24MarketClientCategoryTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private HtmlImageExtractor imageExtractor;
	@Mock
	private Cafe24CategoryResolver categoryResolver;

	private Cafe24MarketClient client;

	private static final String OK_RESPONSE =
		"{\"product\":{\"product_no\":\"999\",\"product_code\":\"P000000AB\"}}";

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
	@DisplayName("컨텍스트에 카테고리가 없으면 자동 해석을 시도해 등록에 반영한다")
	void noCategoryInContext_resolvesAutomatically() {
		when(categoryResolver.resolve(any(), any(), any()))
			.thenReturn(new MarketCategory("55", "건강기능식품 > 비타민", true));
		when(cafe24RestClient.post(eq("/admin/products"), any())).thenReturn(OK_RESPONSE);

		client.publish(product(), MarketPublishContext.empty());

		// Product.create()가 조립하는 productName은 "브랜드 베이스명, 용량, 묶음수" 형태라 정확한 문자열은
		// 이 테스트의 관심사가 아니다 — 브랜드만 정확히 검증하고 나머지는 any()로 둔다.
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
	@DisplayName("컨텍스트에 카테고리가 이미 있으면 자동 해석을 건너뛴다(초안 등록 경로 회귀 방지)")
	void contextHasCategory_skipsResolver() {
		when(cafe24RestClient.post(eq("/admin/products"), any())).thenReturn(OK_RESPONSE);
		MarketPublishContext context =
			new MarketPublishContext("77", "건강기능식품", null, List.of(), java.util.Map.of(), java.util.Map.of());

		client.publish(product(), context);

		verify(categoryResolver, never()).resolve(any(), any(), any());
	}
}
