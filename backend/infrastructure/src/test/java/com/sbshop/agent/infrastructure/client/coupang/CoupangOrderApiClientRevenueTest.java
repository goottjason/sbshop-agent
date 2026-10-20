package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoupangOrderApiClientRevenueTest {
	private HttpServer server;

	@AfterEach
	void stop() {
		if (server != null)
			server.stop(0);
	}

	@Test
	@DisplayName("D-348: 매출내역 API가 거부하면 빈 목록이 아니라 예외다 — '대상 없음 성공'으로 숨기지 않는다")
	void revenueHistoryRejectionPropagates() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			byte[] bytes = "{\"code\":400,\"message\":\"The request is invalid: Date range period must be less than 1 months\"}"
				.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
			exchange.sendResponseHeaders(400, bytes.length);
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

		assertThatThrownBy(() -> client.querySalesDetails(credential, "2026-09-19", "2026-10-19"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("less than 1 months");
	}
}
