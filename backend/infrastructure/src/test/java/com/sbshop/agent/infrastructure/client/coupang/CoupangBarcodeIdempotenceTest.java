package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketPresence;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangBarcodeIdempotenceTest {

	@Mock
	private CoupangRestClient restClient;

	private CoupangMarketClient client;

	private static final String ITEM_ID = "14813281404";
	private static final String BARCODE = "737870163961";

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(null, new ObjectMapper(), restClient, null,
			null, null, null, null, new CoupangAttributeValueResolver());
	}

	private Product productWith(String barcode) {
		Product product = org.mockito.Mockito.mock(Product.class);
		when(product.getProductSpec()).thenReturn(ProductSpec.builder().barcode(barcode).build());
		return product;
	}

	private String responseWithBarcode(String barcode, String statusName) {
		String bc = barcode == null ? "null" : "\"" + barcode + "\"";
		return "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":" + ITEM_ID
			+ ",\"statusName\":\"" + statusName + "\",\"items\":[{\"barcode\":" + bc
			+ ",\"emptyBarcode\":false,\"attributes\":[]}]}}";
	}

	@Test
	@DisplayName("D-233: 마켓에 이미 같은 바코드가 있으면 PUT 하지 않는다 — 쿠팡은 PUT 이 곧 심사중 전환이다")
	void alreadyCorrect_skipsPut() {
		when(restClient.get(anyString())).thenReturn(responseWithBarcode(BARCODE, "승인완료"));

		boolean written = client.syncBarcode(productWith(BARCODE), ITEM_ID, new HashMap<>());

		assertThat(written).isFalse();
		verify(restClient, never()).put(anyString(), any());
	}

	@Test
	@DisplayName("D-233: 바코드가 다르면 전송한다")
	void differentBarcode_writes() {
		when(restClient.get(anyString())).thenReturn(responseWithBarcode("000000000000", "승인완료"));
		when(restClient.put(anyString(), any())).thenReturn("{\"code\":\"SUCCESS\"}");

		boolean written = client.syncBarcode(productWith(BARCODE), ITEM_ID, new HashMap<>());

		assertThat(written).isTrue();
		verify(restClient).put(anyString(), any());
	}

	@Test
	@DisplayName("D-233: 마켓 바코드가 비어 있으면 전송한다")
	void emptyBarcode_writes() {
		when(restClient.get(anyString())).thenReturn(responseWithBarcode(null, "승인완료"));
		when(restClient.put(anyString(), any())).thenReturn("{\"code\":\"SUCCESS\"}");

		boolean written = client.syncBarcode(productWith(BARCODE), ITEM_ID, new HashMap<>());

		assertThat(written).isTrue();
	}

	@Test
	@DisplayName("D-232: statusName 이 상품삭제면 존재하지 않는 것으로 판정한다")
	void deletedStatus_isAbsent() {
		when(restClient.get(anyString())).thenReturn(responseWithBarcode(BARCODE, "상품삭제"));

		assertThat(client.checkPresence(ITEM_ID)).isEqualTo(MarketPresence.ABSENT);
	}

	@Test
	@DisplayName("D-232: 정상 상태면 존재로 판정한다")
	void normalStatus_isPresent() {
		when(restClient.get(anyString())).thenReturn(responseWithBarcode(BARCODE, "심사중"));

		assertThat(client.checkPresence(ITEM_ID)).isEqualTo(MarketPresence.PRESENT);
	}
}
