package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.config.MarketRegistrationDefaults;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreAddressBookResolver;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreCategoryResolver;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreProductPayloadBuilder;
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
class SmartstoreMarketClientPublishMergeTest {

	@Mock
	private SmartstoreProductPayloadBuilder payloadBuilder;
	@Mock
	private SmartstoreCategoryResolver categoryResolver;
	@Mock
	private SmartstoreAddressBookResolver addressBookResolver;
	@Mock
	private MarketRegistrationDefaults defaults;
	@Mock
	private SmartstoreRestClient restClient;
	@Mock
	private Product product;

	private SmartstoreMarketClient client;

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(
			payloadBuilder, categoryResolver, addressBookResolver, defaults,
			restClient, new ObjectMapper());
	}

	@Test
	@DisplayName("완전한 컨텍스트(카테고리+extraFields 채움)는 그대로 payload로 가고 자동조회를 부르지 않는다")
	void fullContext_passesThroughWithoutAutoLookup() {
		MarketPublishContext full = new MarketPublishContext(
			"50000123", "건강기능식품 > 비타민", new BigDecimal("29900"),
			List.of("비타민C"), Map.of("noticeType", "HEALTH_FUNCTIONAL_FOOD"),
			Map.of("shippingAddressId", "111", "returnAddressId", "222",
				"afterServiceTelephoneNumber", "010-0000-0000"));

		when(payloadBuilder.build(any(), any())).thenReturn(Map.of("originProduct", Map.of()));
		when(restClient.post(any(), any())).thenReturn("{\"originProductNo\":\"999\"}");

		client.publish(product, full);

		ArgumentCaptor<MarketPublishContext> captor = ArgumentCaptor.forClass(MarketPublishContext.class);
		verify(payloadBuilder).build(eq(product), captor.capture());
		assertThat(captor.getValue()).isSameAs(full);
		verifyNoInteractions(categoryResolver, addressBookResolver);
	}

	@Test
	@DisplayName("부분 컨텍스트(판매가만)는 autoContext로 빈 칸을 채우되 context.salePrice는 유지한다")
	void partialContext_fillsBlanksButKeepsSalePrice() {
		MarketPublishContext partial = new MarketPublishContext(
			null, null, new BigDecimal("103000"), List.of(), Map.of(), Map.of());

		when(product.getProductName()).thenReturn("테스트 상품");
		when(product.getBrand()).thenReturn("테스트 브랜드");
		when(categoryResolver.resolve(null, "테스트 상품", "테스트 브랜드"))
			.thenReturn(new MarketCategory("50000999", "건강기능식품", true));
		when(addressBookResolver.resolve())
			.thenReturn(Map.of("shippingAddressId", "111", "returnAddressId", "222"));
		when(defaults.getSmartstoreAfterServiceTelephone()).thenReturn("010-1234-5678");
		when(defaults.getSmartstoreAfterServiceGuide()).thenReturn("문의 안내");
		when(defaults.getSmartstoreReturnDeliveryFee()).thenReturn(7000);
		when(defaults.getSmartstoreExchangeDeliveryFee()).thenReturn(14000);
		when(defaults.getSmartstoreOriginAreaCode()).thenReturn("0200037");
		when(payloadBuilder.build(any(), any())).thenReturn(Map.of("originProduct", Map.of()));
		when(restClient.post(any(), any())).thenReturn("{\"originProductNo\":\"999\"}");

		client.publish(product, partial);

		ArgumentCaptor<MarketPublishContext> captor = ArgumentCaptor.forClass(MarketPublishContext.class);
		verify(payloadBuilder).build(eq(product), captor.capture());
		MarketPublishContext merged = captor.getValue();
		assertThat(merged.salePrice()).isEqualByComparingTo("103000");
		assertThat(merged.categoryId()).isEqualTo("50000999");
		assertThat(merged.extraFields()).containsEntry("shippingAddressId", "111");
		assertThat(merged.extraFields()).containsEntry("returnAddressId", "222");
		assertThat(merged.extraFields()).containsEntry("afterServiceTelephoneNumber", "010-1234-5678");
	}
}
