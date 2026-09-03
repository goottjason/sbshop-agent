package com.sbshop.agent.infrastructure.client.smartstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartStorePerItemResultTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private JsonNode json(String raw) throws Exception {
		return MAPPER.readTree(raw);
	}

	@Test
	@DisplayName("D-288: 취소가 거부된 건이 있으면 예외를 던진다 — 200 이라고 성공이 아니다")
	void cancelRejected_throws() throws Exception {
		JsonNode root = json("""
			{"detail":[{"productOrderId":"2026090300001","cancel":false}]}""");

		assertThatThrownBy(() -> SmartStoreOrderApiClient.requireAllSucceeded(root, "cancel", "주문취소"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("2026090300001")
			.hasMessageContaining("주문취소");
	}

	@Test
	@DisplayName("D-288: 거부된 건이 여럿이면 모두 알린다 — 하나만 보고 끝내지 않는다")
	void multipleRejected_allReported() throws Exception {
		JsonNode root = json("""
			{"detail":[
			  {"productOrderId":"A1","cancel":false},
			  {"productOrderId":"A2","cancel":true},
			  {"productOrderId":"A3","cancel":false}]}""");

		assertThatThrownBy(() -> SmartStoreOrderApiClient.requireAllSucceeded(root, "cancel", "주문취소"))
			.hasMessageContaining("A1")
			.hasMessageContaining("A3");
	}

	@Test
	@DisplayName("D-288: 전건 성공이면 통과한다")
	void allSucceeded_passes() throws Exception {
		JsonNode root = json("""
			{"detail":[{"productOrderId":"A1","cancel":true},{"productOrderId":"A2","cancel":true}]}""");

		assertThatCode(() -> SmartStoreOrderApiClient.requireAllSucceeded(root, "cancel", "주문취소"))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("D-288: 발주확인도 같은 규율을 받는다 — 같은 결함이 두 메서드에 있었다")
	void confirmRejected_throws() throws Exception {
		JsonNode root = json("""
			{"detail":[{"productOrderId":"B9","confirm":false}]}""");

		assertThatThrownBy(() -> SmartStoreOrderApiClient.requireAllSucceeded(root, "confirm", "발주확인"))
			.hasMessageContaining("B9")
			.hasMessageContaining("발주확인");
	}

	@Test
	@DisplayName("D-288: detail 이 없으면 던지지 않는다 — 관측된 적 없는 응답 모양에 라이브 경로를 걸지 않는다")
	void missingDetail_doesNotThrow() throws Exception {
		assertThatCode(() -> SmartStoreOrderApiClient.requireAllSucceeded(json("{}"), "cancel", "주문취소"))
			.doesNotThrowAnyException();
		assertThatCode(() -> SmartStoreOrderApiClient.requireAllSucceeded(json("{\"detail\":[]}"), "cancel", "주문취소"))
			.doesNotThrowAnyException();
	}
}
