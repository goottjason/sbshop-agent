package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Cafe24RestClientErrorMessageTest {

	private static final String ERROR_BODY = "{\"error\":{\"code\":422,\"message\":\"You cannot change to that order state.\"}}";

	private HttpServer server;
	private Cafe24RestClient client;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] body = ERROR_BODY.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(422, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		server.start();

		Cafe24TokenManager tokenManager = mock(Cafe24TokenManager.class);
		lenient().when(tokenManager.getValidAccessToken()).thenReturn("test-token");
		when(tokenManager.getApiUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
		client = new Cafe24RestClient(tokenManager);
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	@DisplayName("POST 실패 메시지에 상태코드와 응답 본문이 실린다")
	void postCarriesStatusAndBody() {
		assertThatThrownBy(() -> client.post("/admin/orders/O1/shipments", Map.of("request", Map.of())))
			.isInstanceOf(RuntimeException.class)
			.satisfies(e -> {
				assertThat(e.getMessage()).contains("422");
				assertThat(e.getMessage()).contains("You cannot change to that order state.");
			});
	}

	@Test
	@DisplayName("PUT 실패 메시지에 상태코드와 응답 본문이 실린다")
	void putCarriesStatusAndBody() {
		assertThatThrownBy(() -> client.put("/admin/orders/O1", Map.of("request", Map.of())))
			.isInstanceOf(RuntimeException.class)
			.satisfies(e -> {
				assertThat(e.getMessage()).contains("422");
				assertThat(e.getMessage()).contains("You cannot change to that order state.");
			});
	}

	@Test
	@DisplayName("DELETE 실패 메시지에 상태코드와 응답 본문이 실린다")
	void deleteCarriesStatusAndBody() {
		assertThatThrownBy(() -> client.delete("/admin/products/1"))
			.isInstanceOf(RuntimeException.class)
			.satisfies(e -> {
				assertThat(e.getMessage()).contains("422");
				assertThat(e.getMessage()).contains("You cannot change to that order state.");
			});
	}

	@Test
	@DisplayName("GET은 종전대로 상태코드와 본문을 싣는다(회귀)")
	void getKeepsCarryingStatusAndBody() {
		assertThatThrownBy(() -> client.get("/admin/orders/O1"))
			.isInstanceOf(RuntimeException.class)
			.satisfies(e -> {
				assertThat(e.getMessage()).contains("422");
				assertThat(e.getMessage()).contains("You cannot change to that order state.");
			});
	}
}
