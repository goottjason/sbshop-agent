package com.sbshop.agent.infrastructure.client.cafe24;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24OrderApiClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24OrderApiClientStatusTest {

	@Mock Cafe24RestClient restClient;

	@Test
	@DisplayName("acceptOrder는 PUT /admin/orders/{id}에 배송준비 상태(N20)를 보낸다")
	void acceptOrderSendsPut() {
		var client = new Cafe24OrderApiClient(restClient, new ObjectMapper());
		client.acceptOrder("O123");
		verify(restClient).put(eq("/admin/orders/O123"),
			ArgumentMatchers.argThat(body -> bodyStatus(body).equals("N20")));
	}

	@Test
	@DisplayName("cancelOrder는 PUT /admin/orders/{id}에 취소 상태(C40)를 보낸다")
	void cancelOrderSendsPut() {
		var client = new Cafe24OrderApiClient(restClient, new ObjectMapper());
		client.cancelOrder("O123");
		verify(restClient).put(eq("/admin/orders/O123"),
			ArgumentMatchers.argThat(body -> bodyStatus(body).equals("C40")));
	}

	@SuppressWarnings("unchecked")
	private String bodyStatus(Object body) {
		Map<String, Object> req = (Map<String, Object>) ((Map<String, Object>) body).get("request");
		return String.valueOf(req.get("status"));
	}
}
