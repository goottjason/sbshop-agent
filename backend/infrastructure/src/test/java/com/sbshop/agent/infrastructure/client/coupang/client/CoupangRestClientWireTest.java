package com.sbshop.agent.infrastructure.client.coupang.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.coupang.CoupangHmacUtil;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangRestClientWireTest {

	private static final String ACCESS_KEY = "test-access-key";
	private static final String SECRET_KEY = "test-secret-key";
	private static final String VENDOR_ID = "A00123456";
	private static final String SELLER_PRODUCTS = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
	private static final String DELETE_PATH = SELLER_PRODUCTS + "/14813282340";
	private static final String APPROVAL_PATH = DELETE_PATH + "/approvals";
	private static final String RESPONSE_BODY = "{\"code\":\"SUCCESS\"}";

	@Mock
	private CoupangProperties properties;
	@Mock
	private MarketCredentialRepository marketCredentialRepository;

	private HttpServer server;
	private final List<Recorded> received = new CopyOnWriteArrayList<>();
	private CoupangRestClient client;

	private record Recorded(String method, String path, String contentLength, String transferEncoding,
		String upgrade, String contentType, String authorization, String requestedBy, String body) {
	}

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			received.add(new Recorded(
				exchange.getRequestMethod(),
				exchange.getRequestURI().toString(),
				exchange.getRequestHeaders().getFirst("Content-Length"),
				exchange.getRequestHeaders().getFirst("Transfer-Encoding"),
				exchange.getRequestHeaders().getFirst("Upgrade"),
				exchange.getRequestHeaders().getFirst("Content-Type"),
				exchange.getRequestHeaders().getFirst("Authorization"),
				exchange.getRequestHeaders().getFirst("X-Requested-By"),
				new String(body, StandardCharsets.UTF_8)));
			byte[] response = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();

		when(properties.getApiUrl()).thenReturn("http://" + InetAddress.getLoopbackAddress().getHostAddress()
			+ ":" + server.getAddress().getPort());
		when(properties.getAccessKey()).thenReturn(ACCESS_KEY);
		when(properties.getSecretKey()).thenReturn(SECRET_KEY);
		when(properties.getVendorId()).thenReturn(VENDOR_ID);
		when(marketCredentialRepository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.empty());

		client = new CoupangRestClient(properties, marketCredentialRepository);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	@DisplayName("본문 없는 PUT(승인 요청)은 HTTP/2 승격 없이 Content-Length: 0 을 보낸다")
	void bodilessPutSendsZeroContentLengthWithoutHttp2Upgrade() {
		client.requestWithBody("PUT", APPROVAL_PATH, null);

		Recorded request = only();
		assertThat(request.method()).isEqualTo("PUT");
		assertThat(request.path()).isEqualTo(APPROVAL_PATH);
		assertThat(request.contentLength()).isEqualTo("0");
		assertThat(request.transferEncoding()).isNull();
		assertThat(request.upgrade()).isNull();
		assertThat(request.body()).isEmpty();
	}

	@Test
	@DisplayName("본문 없는 POST 도 같은 경로를 탄다")
	void bodilessPostSendsZeroContentLengthWithoutHttp2Upgrade() {
		client.post(APPROVAL_PATH, null);

		Recorded request = only();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.contentLength()).isEqualTo("0");
		assertThat(request.upgrade()).isNull();
		assertThat(request.body()).isEmpty();
	}

	@Test
	@DisplayName("본문 없는 DELETE 는 전송 방식이 바뀌지 않는다")
	void bodilessDeleteKeepsDefaultTransport() {
		client.requestWithBody("DELETE", DELETE_PATH, null);

		Recorded request = only();
		assertThat(request.method()).isEqualTo("DELETE");
		assertThat(request.upgrade()).isEqualTo("h2c");
		assertThat(request.body()).isEmpty();
	}

	@Test
	@DisplayName("본문 있는 POST 는 JSON 본문과 전송 방식이 그대로다")
	void bodiedPostKeepsJsonBody() {
		client.post(SELLER_PRODUCTS, Map.of("sellerProductName", "sb"));

		Recorded request = only();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.contentType()).startsWith("application/json");
		assertThat(request.body()).isEqualTo("{\"sellerProductName\":\"sb\"}");
		assertThat(request.upgrade()).isEqualTo("h2c");
		assertThat(request.transferEncoding()).isEqualTo("chunked");
		assertThat(request.requestedBy()).isEqualTo(VENDOR_ID);
	}

	@Test
	@DisplayName("본문 있는 PUT 도 전송 방식이 그대로다")
	void bodiedPutKeepsDefaultTransport() {
		client.put(DELETE_PATH, Map.of("sellerProductName", "sb"));

		Recorded request = only();
		assertThat(request.method()).isEqualTo("PUT");
		assertThat(request.contentType()).startsWith("application/json");
		assertThat(request.body()).isEqualTo("{\"sellerProductName\":\"sb\"}");
		assertThat(request.upgrade()).isEqualTo("h2c");
	}

	@Test
	@DisplayName("본문 없는 GET 은 본문도 Content-Length 도 붙지 않는다")
	void bodilessGetStaysBodiless() {
		client.get(DELETE_PATH);

		Recorded request = only();
		assertThat(request.method()).isEqualTo("GET");
		assertThat(request.body()).isEmpty();
		assertThat(request.contentLength()).isNull();
		assertThat(request.contentType()).isNull();
		assertThat(request.upgrade()).isEqualTo("h2c");
	}

	@Test
	@DisplayName("서명은 본문 유무와 무관하다 — 메서드·경로만 서명한다")
	void signatureIgnoresBody() {
		String before = CoupangHmacUtil.generateSignatureUtc("PUT", APPROVAL_PATH, ACCESS_KEY, SECRET_KEY);
		client.requestWithBody("PUT", APPROVAL_PATH, null);
		String after = CoupangHmacUtil.generateSignatureUtc("PUT", APPROVAL_PATH, ACCESS_KEY, SECRET_KEY);

		assertThat(only().authorization()).isIn(before, after);
	}

	private Recorded only() {
		assertThat(received).hasSize(1);
		return received.get(0);
	}
}
