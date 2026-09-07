package com.sbshop.agent.infrastructure.client.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketPreparationRequestScope;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import com.sbshop.agent.infrastructure.client.smartstore.config.SmartstoreProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class MarketPreparationTransportTest {
	HttpServer server;
	String base;
	AtomicInteger received = new AtomicInteger();
	boolean limited;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			received.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			if (limited)
				exchange.getResponseHeaders().set("Retry-After", "120");
			exchange.sendResponseHeaders(limited ? 429 : 200, body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	Object client(MarketType market) {
		var credentials = mock(MarketCredentialRepository.class);
		when(credentials.findByMarketType(any())).thenReturn(Optional.empty());
		return switch (market) {
			case SMART_STORE -> {
				var p = new SmartstoreProperties();
				p.setApiUrl(base);
				p.setClientId("fixture-id");
				var c = new SmartstoreRestClient(p, new ObjectMapper(), credentials);
				ReflectionTestUtils.setField(c, "accessToken", "fixture-token");
				ReflectionTestUtils.setField(c, "tokenClientId", "fixture-id");
				ReflectionTestUtils.setField(c, "tokenExpiresAt", Instant.now().plusSeconds(900));
				yield c;
			}
			case COUPANG -> {
				var p = new CoupangProperties();
				p.setApiUrl(base);
				p.setAccessKey("fixture-key");
				p.setSecretKey("fixture-secret");
				p.setVendorId("fixture-vendor");
				yield new CoupangRestClient(p, credentials);
			}
			case CAFE24 -> {
				var token = mock(Cafe24TokenManager.class);
				when(token.getApiUrl()).thenReturn(base);
				when(token.getValidAccessToken()).thenReturn("fixture-token");
				yield new Cafe24RestClient(token);
			}
			default -> throw new IllegalArgumentException();
		};
	}

	void get(Object client) {
		if (client instanceof SmartstoreRestClient c)
			c.get("/fixture");
		else if (client instanceof CoupangRestClient c)
			c.get("/fixture");
		else
			((Cafe24RestClient)client).get("/fixture");
	}

	@ParameterizedTest
	@EnumSource(value = MarketType.class, names = {"SMART_STORE", "COUPANG", "CAFE24"})
	void actualRestClientInvokesFenceBeforeEveryRequestAndScopeCleanupPreservesLegacyCalls(MarketType market) {
		Object c = client(market);
		AtomicInteger checked = new AtomicInteger();
		try (var scope = MarketPreparationRequestScope.open(market, () -> {
			if (checked.incrementAndGet() > 1)
				throw new MarketPreparationRequestScope.Blocked("lost lease");
		}, retry -> {})) {
			get(c);
			assertThatThrownBy(() -> get(c)).hasStackTraceContaining("lost lease");
		}
		assertThat(received.get()).isEqualTo(1);
		get(c);
		assertThat(received.get()).isEqualTo(2);
		assertThat(checked.get()).isEqualTo(2);
	}

	@ParameterizedTest
	@EnumSource(value = MarketType.class, names = {"SMART_STORE", "COUPANG", "CAFE24"})
	void real429IsObservedBeforeAdapterWrappingAndStopsNextPhysicalRequest(MarketType market) {
		Object c = client(market);
		limited = true;
		AtomicReference<Instant> cooldown = new AtomicReference<>();
		try (var scope = MarketPreparationRequestScope.open(market, () -> {
			if (cooldown.get() != null)
				throw new MarketPreparationRequestScope.Blocked("cooldown");
		}, cooldown::set)) {
			assertThatThrownBy(() -> get(c)).isInstanceOf(RuntimeException.class);
			assertThat(cooldown.get()).isAfter(Instant.now().plusSeconds(110));
			assertThatThrownBy(() -> get(c)).hasStackTraceContaining("cooldown");
		}
		assertThat(received.get()).isEqualTo(1);
	}

	@Test
	void scopeBlocksImageUploadAndBodilessPostBeforeWire() {
		Runnable stop = () -> {
			throw new MarketPreparationRequestScope.Blocked("no write");
		};
		var cafe = (Cafe24RestClient)client(MarketType.CAFE24);
		try (var scope = MarketPreparationRequestScope.open(MarketType.CAFE24, stop, retry -> {})) {
			assertThatThrownBy(
				() -> cafe.post("/admin/products/images", Map.of("requests", List.of(Map.of("image", "fixture")))))
				.hasStackTraceContaining("no write");
		}
		var ss = (SmartstoreRestClient)client(MarketType.SMART_STORE);
		try (var scope = MarketPreparationRequestScope.open(MarketType.SMART_STORE, stop, retry -> {})) {
			assertThatThrownBy(() -> ss.uploadImages(new org.springframework.util.LinkedMultiValueMap<>()))
				.hasStackTraceContaining("no write");
		}
		var cp = (CoupangRestClient)client(MarketType.COUPANG);
		try (var scope = MarketPreparationRequestScope.open(MarketType.COUPANG, stop, retry -> {})) {
			assertThatThrownBy(() -> cp.requestWithBody("POST", "/fixture", null)).hasStackTraceContaining("no write");
		}
		assertThat(received.get()).isZero();
	}

	@Test
	void differentMarketAndNestedScopeDoNotEscapeTheOriginalFence() {
		var c = client(MarketType.CAFE24);
		try (var scope = MarketPreparationRequestScope.open(MarketType.COUPANG, () -> {}, retry -> {})) {
			assertThatThrownBy(() -> get(c)).hasStackTraceContaining("마켓");
			assertThatThrownBy(() -> MarketPreparationRequestScope.open(MarketType.CAFE24, () -> {}, retry -> {}))
				.isInstanceOf(MarketPreparationRequestScope.Blocked.class);
		}
		assertThat(received.get()).isZero();
		get(c);
		assertThat(received.get()).isEqualTo(1);
	}
}
