package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
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
class CoupangDeleteFlowTest {

	@Mock
	private CoupangRestClient restClient;

	private CoupangMarketClient client;

	private static final String SPID = "14292091136";
	private static final String VENDOR_ITEM = "86789004811";
	private static final String NOT_DELETABLE =
		"{\"code\":\"ERROR\",\"message\":\"업체상품[" + SPID + "]이 없거나 삭제가 불가능한 상태입니다."
			+ " 삭제는 '저장중', '임시저장' 상태에서만 가능합니다.\"}";
	private static final String OK = "{\"code\":\"SUCCESS\",\"message\":\"삭제되었습니다.\"}";

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(null, new ObjectMapper(), restClient, null,
			null, null, null, null, new CoupangAttributeValueResolver());
	}

	private String productJson(String statusName) {
		return "{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":" + SPID
			+ ",\"statusName\":\"" + statusName + "\",\"items\":[{\"vendorItemId\":" + VENDOR_ITEM + "}]}}";
	}

	@Test
	@DisplayName("D-244: 삭제 불가 상태면 판매중지 후 다시 삭제한다 — 쿠팡은 승인 상품을 바로 못 지운다")
	void notDeletable_stopsSalesThenRetriesDelete() {
		when(restClient.requestWithBody(eq("DELETE"), anyString(), any()))
			.thenThrow(new RuntimeException(NOT_DELETABLE))
			.thenReturn(OK);
		when(restClient.get(anyString())).thenReturn(productJson("승인완료"));
		when(restClient.put(contains("/sales/stop"), any())).thenReturn(OK);

		client.deleteFromMarket(SPID);

		verify(restClient).put(contains("/vendor-items/" + VENDOR_ITEM + "/sales/stop"), any());
		verify(restClient, org.mockito.Mockito.times(2))
			.requestWithBody(eq("DELETE"), anyString(), any());
	}

	@Test
	@DisplayName("D-244: 이미 삭제된 상품은 성공으로 본다 — '없거나'의 앞쪽 가지다")
	void alreadyDeleted_isTreatedAsSuccess() {
		when(restClient.requestWithBody(eq("DELETE"), anyString(), any()))
			.thenThrow(new RuntimeException(NOT_DELETABLE));
		when(restClient.get(anyString())).thenReturn(productJson("상품삭제"));

		client.deleteFromMarket(SPID);

		verify(restClient, never()).put(contains("/sales/stop"), any());
	}

	@Test
	@DisplayName("D-244: 판매중지 후에도 못 지우면 실패로 남긴다 — 판매중지를 삭제로 인정하지 않는다")
	void stillNotDeletable_fails() {
		when(restClient.requestWithBody(eq("DELETE"), anyString(), any()))
			.thenThrow(new RuntimeException(NOT_DELETABLE));
		when(restClient.get(anyString())).thenReturn(productJson("승인완료"));
		when(restClient.put(contains("/sales/stop"), any())).thenReturn(OK);

		assertThatThrownBy(() -> client.deleteFromMarket(SPID))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("삭제");
	}
}
