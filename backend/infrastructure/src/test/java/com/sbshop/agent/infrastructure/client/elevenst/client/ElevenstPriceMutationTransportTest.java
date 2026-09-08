package com.sbshop.agent.infrastructure.client.elevenst.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.infrastructure.client.elevenst.config.ElevenstProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class ElevenstPriceMutationTransportTest {
	HttpServer server;
	ElevenstProperties properties;
	ElevenstMarketRestClient rest;
	final String path = "/rest/prodservices/product/price/123/10000";
	final AtomicInteger requests = new AtomicInteger();
	final AtomicInteger admissions = new AtomicInteger();
	final AtomicBoolean correctRequest = new AtomicBoolean();
	int status = 200;
	boolean disconnect;
	String expectedMethod = "GET";
	String expectedPath = path;
	String expectedBody = "";

	@BeforeEach
	void setup() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			correctRequest.set(exchange.getRequestMethod().equals(expectedMethod)
				&& exchange.getRequestURI().toString().equals(expectedPath)
				&& new String(exchange.getRequestBody().readAllBytes(), Charset.forName("EUC-KR")).equals(expectedBody)
				&& admissions.get() == 1);
			if (disconnect) {
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().add("Retry-After", status == 429 ? "600" : "0");
			exchange.getResponseHeaders().add("Location", "/unexpected-second-write");
			byte[] body = "<ClientMessage><message>가격 변경 응답</message></ClientMessage>"
				.getBytes(Charset.forName("EUC-KR"));
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		properties = new ElevenstProperties();
		properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
		properties.setApiKey("local-fixture-account");
		var credentials = mock(MarketCredentialRepository.class);
		when(credentials.findByMarketType(any())).thenReturn(Optional.empty());
		rest = new ElevenstMarketRestClient(properties, credentials);
	}

	@AfterEach
	void cleanup() {
		server.stop(0);
	}

	@Test
	void admitsImmediatelyBeforeExactlyOneBodylessGetAndDecodesEucKr() {
		String response = rest.mutatePriceOnce(path, rest.accountReference(), admissions::incrementAndGet);
		assertThat(response).contains("가격 변경 응답");
		assertThat(requests.get()).isEqualTo(1);
		assertThat(admissions.get()).isEqualTo(1);
		assertThat(correctRequest.get()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(ints = {301, 302, 307, 308, 408, 429, 503})
	void redirectAndRetryAfterZeroNeverCauseSecondMutation(int code) {
		status = code;
		assertThatThrownBy(() -> rest.mutatePriceOnce(path, rest.accountReference(), admissions::incrementAndGet))
			.isInstanceOfSatisfying(RestClientResponseException.class, error -> {
				assertThat(error.getStatusCode().value()).isEqualTo(code);
				assertThat(error.getResponseHeaders().getFirst("Retry-After")).isEqualTo(code == 429 ? "600" : "0");
			});
		assertThat(requests.get()).isEqualTo(1);
		assertThat(admissions.get()).isEqualTo(1);
		assertThat(correctRequest.get()).isTrue();
	}

	@Test
	void lostResponseCannotTriggerAutomaticGetReplay() {
		disconnect = true;
		assertThatThrownBy(() -> rest.mutatePriceOnce(path, rest.accountReference(), admissions::incrementAndGet))
			.isInstanceOf(ResourceAccessException.class);
		assertThat(requests.get()).isEqualTo(1);
		assertThat(admissions.get()).isEqualTo(1);
	}

	@Test
	void guardAbortIsIdenticalAndNoRequestReachesServer() {
		var abort = new IllegalStateException("fixture lease expired");
		assertThatThrownBy(() -> rest.mutatePriceOnce(path, rest.accountReference(), () -> {
			throw abort;
		})).isSameAs(abort);
		assertThat(requests.get()).isZero();
	}

	@Test
	void accountRotationInsideAdmissionPreventsOldOrNewAccountWrite() {
		assertThatThrownBy(() -> rest.mutatePriceOnce(path, rest.accountReference(),
			() -> properties.setApiKey("rotated-fixture-account")))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(requests.get()).isZero();
	}

	@Test
	void pathCannotBeRepurposedForCouponsStateOrInjectedParameters() {
		for (String invalid : java.util.List.of("/rest/prodservices/product/priceCoupon/123/10000/100",
			path + "?selStatCd=103",
			"/rest/prodstatservice/stat/restartdisplay/123", "/rest/prodservices/product/price/123/0"))
			assertThatThrownBy(
				() -> rest.mutatePriceOnce(invalid, rest.accountReference(), admissions::incrementAndGet))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(requests.get()).isZero();
		assertThat(admissions.get()).isZero();
	}

	@Test
	void quantityPutPreservesExactEucKrBodyAndAdmitsOnce() {
		expectedMethod = "PUT";
		expectedPath = "/rest/prodservices/stockqty/456";
		expectedBody = "<ProductStock><prdNo>123</prdNo><stckQty>0</stckQty><memo>확인</memo></ProductStock>";
		rest.mutateStockOnce(expectedPath, expectedBody, rest.accountReference(), admissions::incrementAndGet);
		assertThat(requests.get()).isEqualTo(1);
		assertThat(admissions.get()).isEqualTo(1);
		assertThat(correctRequest.get()).isTrue();
	}

	@Test
	void restartPutCanUseAnEmptyBodyWithoutFollowing503Retry() {
		expectedMethod = "PUT";
		expectedPath = "/rest/prodstatservice/stat/restartdisplay/123";
		status = 503;
		assertThatThrownBy(
			() -> rest.mutateStockOnce(expectedPath, null, rest.accountReference(), admissions::incrementAndGet))
			.isInstanceOf(RestClientResponseException.class);
		assertThat(requests.get()).isEqualTo(1);
		assertThat(admissions.get()).isEqualTo(1);
		assertThat(correctRequest.get()).isTrue();
	}

	@Test
	void lostPutResponseCannotTriggerAutomaticReplay() {
		expectedMethod = "PUT";
		expectedPath = "/rest/prodservices/stockqty/456";
		disconnect = true;
		assertThatThrownBy(
			() -> rest.mutateStockOnce(expectedPath, null, rest.accountReference(), admissions::incrementAndGet))
			.isInstanceOf(ResourceAccessException.class);
		assertThat(requests.get()).isEqualTo(1);
		assertThat(admissions.get()).isEqualTo(1);
	}

	@Test
	void strictReadAlsoRejectsRedirectWithoutForwardingCredentials() {
		status = 302;
		assertThatThrownBy(() -> rest.requestStrict("GET", "/rest/prodmarketservice/prodmarket/123", null))
			.isInstanceOfSatisfying(RestClientResponseException.class,
				error -> assertThat(error.getStatusCode().value()).isEqualTo(302));
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void defaultApiUrlUsesVerifiedHttpsTransport() {
		assertThat(new ElevenstProperties().getApiUrl()).isEqualTo("https://api.11st.co.kr");
	}
}
