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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-096: 스마트스토어 판매자 즉시할인(customerBenefit.immediateDiscountPolicy) 일괄 제거.
 * 저수수료 마켓에 마켓별 가격을 이미 낮게 산정하므로, 별도 즉시할인이 겹치면 이중할인 손해가 난다.
 */
@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientDiscountRemovalTest {

	@Mock private SmartstoreRestClient restClient;

	private SmartstoreMarketClient client;
	private static final String ITEM_ID = "OP123";

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(restClient, new ObjectMapper());
	}

	private String getWithImmediateDiscount() {
		return "{\"originProduct\":{\"salePrice\":65400,\"stockQuantity\":10,"
			+ "\"customerBenefit\":{"
			+ "\"immediateDiscountPolicy\":{\"discountMethod\":{\"value\":9,\"unitType\":\"PERCENT\"}},"
			+ "\"purchasePointPolicy\":{\"value\":1,\"unitType\":\"PERCENT\"}"
			+ "}}}";
	}

	@Test
	@DisplayName("dryRun=false → PUT 바디의 customerBenefit에서 즉시할인만 제거하고 다른 혜택(적립)은 보존한다")
	@SuppressWarnings("unchecked")
	void removesOnlyImmediateDiscount_preservesOtherBenefits() {
		when(restClient.get(any())).thenReturn(getWithImmediateDiscount());
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

		Optional<String> removed = client.removeSellerImmediateDiscount(ITEM_ID, false);

		verify(restClient).put(eq("/v2/products/origin-products/" + ITEM_ID), captor.capture());
		Map<String, Object> originProduct = (Map<String, Object>) captor.getValue().get("originProduct");
		Map<String, Object> customerBenefit = (Map<String, Object>) originProduct.get("customerBenefit");
		assertThat(customerBenefit).doesNotContainKey("immediateDiscountPolicy");
		assertThat(customerBenefit).containsKey("purchasePointPolicy");
		assertThat(removed).isPresent();
		assertThat(removed.get()).contains("9");
	}

	@Test
	@DisplayName("dryRun=true → PUT을 호출하지 않고 현재 즉시할인만 보고한다")
	void dryRun_reportsButDoesNotPut() {
		when(restClient.get(any())).thenReturn(getWithImmediateDiscount());

		Optional<String> found = client.removeSellerImmediateDiscount(ITEM_ID, true);

		verify(restClient, never()).put(any(), any());
		assertThat(found).isPresent();
		assertThat(found.get()).contains("9");
	}

	@Test
	@DisplayName("즉시할인이 없으면 empty 반환·PUT 호출 없음(멱등)")
	void noDiscount_returnsEmpty_noPut() {
		when(restClient.get(any())).thenReturn(
			"{\"originProduct\":{\"salePrice\":65400,\"stockQuantity\":10,"
				+ "\"customerBenefit\":{\"purchasePointPolicy\":{\"value\":1}}}}");

		Optional<String> found = client.removeSellerImmediateDiscount(ITEM_ID, false);

		verify(restClient, never()).put(any(), any());
		assertThat(found).isEmpty();
	}
}
