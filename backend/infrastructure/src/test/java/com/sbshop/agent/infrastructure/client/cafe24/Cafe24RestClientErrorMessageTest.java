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
	void consecutiveProductReadsDoNotReuseThePreWriteCacheKey() {
		server.removeContext("/");
		var seen = new java.util.concurrent.ConcurrentHashMap<String, String>();
		var price = new java.util.concurrent.atomic.AtomicInteger(10000);
		var queries = new java.util.concurrent.CopyOnWriteArrayList<String>();
		server.createContext("/", exchange -> {
			String key = exchange.getRequestURI().toString();
			queries.add(key);
			String value = seen.computeIfAbsent(key, ignored -> "{\"price\":" + price.get() + "}");
			byte[] body = value.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		assertThat(client.get("/admin/products/123?shop_no=1")).contains("10000");
		price.set(12000);
		assertThat(client.get("/admin/products/123?shop_no=1")).contains("12000");
		assertThat(queries).hasSize(2).doesNotHaveDuplicates();
		assertThat(queries).allMatch(q -> q.startsWith("/admin/products/123?shop_no=1&_sb_read="));
	}

	@Test
	@DisplayName("D-346: 주문 목록·상세 조회도 매번 다른 캐시 키로 읽어 발주확인 전 응답을 다시 받지 않는다")
	void consecutiveOrderReadsDoNotReuseTheCacheKey() {
		server.removeContext("/");
		var seen = new java.util.concurrent.ConcurrentHashMap<String, String>();
		var status = new java.util.concurrent.atomic.AtomicReference<>("N10");
		var queries = new java.util.concurrent.CopyOnWriteArrayList<String>();
		server.createContext("/", exchange -> {
			String key = exchange.getRequestURI().toString();
			queries.add(key);
			String value = seen.computeIfAbsent(key, ignored -> "{\"order_status\":\"" + status.get() + "\"}");
			byte[] body = value.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		String list = "/admin/orders?embed=items,receivers,buyer&date_type=order_date&limit=100&offset=0";
		assertThat(client.get(list)).contains("N10");
		assertThat(client.get("/admin/orders/O1?embed=items")).contains("N10");
		status.set("N20");
		assertThat(client.get(list)).contains("N20");
		assertThat(client.get("/admin/orders/O1?embed=items")).contains("N20");
		assertThat(queries).hasSize(4).doesNotHaveDuplicates();
		assertThat(queries).allMatch(q -> q.contains("_sb_read="));
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
