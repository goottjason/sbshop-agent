package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoupangOrderApiClientCancelTest {
	private HttpServer server;
	private final List<String> requested = new CopyOnWriteArrayList<>();

	@AfterEach
	void stop() {
		if (server != null)
			server.stop(0);
	}

	@Test
	@DisplayName("D-345: 발주확인 전 취소는 cancelType=CANCEL 로만 조회되므로 반품과 취소를 모두 가져온다")
	void queryReturnsAlsoFetchesCancellationsBeforeOrderConfirm() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			String uri = exchange.getRequestURI().toString();
			requested.add(uri);
			String body = uri.contains("cancelType=CANCEL")
				? "{\"code\":200,\"data\":[{\"orderId\":23103582115744,\"receiptType\":\"CANCEL\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]}"
				: "{\"code\":200,\"data\":[{\"orderId\":24103539929761,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		var client = new CoupangOrderApiClient(new ObjectMapper(),
			"http://127.0.0.1:" + server.getAddress().getPort());
		var credential = mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("A00123456");
		when(credential.getAccessKey()).thenReturn("test-access-key");
		when(credential.getSecretKey()).thenReturn("test-secret-key");

		JsonNode result = client.queryReturns(credential, "2026-10-10", "2026-10-14");

		var types = new ArrayList<String>();
		result.forEach(n -> types.add(n.path("receiptType").asText()));
		assertThat(types).containsExactlyInAnyOrder("RETURN", "CANCEL");
		assertThat(requested).anyMatch(u -> u.contains("cancelType=CANCEL"));
	}

	@Test
	@DisplayName("D-345: 발주확인 응답의 묶음배송 단위 실패(succeed=false)를 성공으로 넘기지 않는다")
	void acceptOrdersFailsWhenCoupangRejectsTheShipmentBox() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			exchange.getRequestBody().readAllBytes();
			byte[] bytes = ("{\"code\":200,\"message\":\"OK\",\"data\":{\"responseCode\":99,\"responseMessage\":\"FAIL\","
				+ "\"responseList\":[{\"shipmentBoxId\":742321943248997,\"succeed\":false,\"resultCode\":\"FAIL\","
				+ "\"resultMessage\":\"취소된 주문입니다.\"}]}}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		var client = new CoupangOrderApiClient(new ObjectMapper(),
			"http://127.0.0.1:" + server.getAddress().getPort());
		var credential = mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("A00123456");
		when(credential.getAccessKey()).thenReturn("test-access-key");
		when(credential.getSecretKey()).thenReturn("test-secret-key");

		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> client.acceptOrders(credential, List.of("742321943248997")))
			.hasMessageContaining("취소된 주문입니다.");
	}
}
