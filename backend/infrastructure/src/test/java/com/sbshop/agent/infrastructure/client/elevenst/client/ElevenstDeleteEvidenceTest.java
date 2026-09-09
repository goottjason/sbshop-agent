package com.sbshop.agent.infrastructure.client.elevenst.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.infrastructure.client.elevenst.config.ElevenstProperties;
import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ElevenstDeleteEvidenceTest {
	@ParameterizedTest
	@ValueSource(ints = {200, 400, 405, 429, 503})
	void recordsStatusAndKoreanBodyWithoutRepeatingDelete(int status) throws Exception {
		var count = new AtomicInteger();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String body = "<ClientMessage><resultCode>403</resultCode><message>삭제할 수 없습니다.</message></ClientMessage>";
		server.createContext("/", exchange -> {
			count.incrementAndGet();
			assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
			exchange.getResponseHeaders().add("Retry-After", "0");
			byte[] bytes = body.getBytes(Charset.forName("EUC-KR"));
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		try {
			var props = new ElevenstProperties();
			props.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
			props.setApiKey("fixture-secret");
			var credentials = mock(MarketCredentialRepository.class);
			when(credentials.findByMarketType(any())).thenReturn(Optional.empty());
			var response = new ElevenstMarketRestClient(props, credentials).deleteRecorded("123");
			assertThat(response.httpStatus()).isEqualTo(status);
			assertThat(response.body()).isEqualTo(body);
			assertThat(response.evidenceId()).isNotBlank();
			assertThat(count).hasValue(1);
		} finally { server.stop(0); }
	}

	@Test
	void rejectionReasonIsKeptForReadbackFailure() {
		var rest = mock(ElevenstMarketRestClient.class);
		when(rest.deleteRecorded("123")).thenReturn(new ElevenstMarketRestClient.DeleteResponse(405,
			"<ClientMessage><resultCode>403</resultCode><message>삭제할 수 없습니다.</message></ClientMessage>", "trace-1"));
		assertThatThrownBy(() -> new ElevenstMarketClient(rest).deleteFromMarket("123"))
			.hasMessageContaining("HTTP 405").hasMessageContaining("코드 403")
			.hasMessageContaining("삭제할 수 없습니다.").hasMessageContaining("trace-1");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "<html>Bad gateway</html>", "<ClientMessage><resultCode>200</resultCode></ClientMessage>"})
	void noResponseEnvelopeAloneIsDeletionProof(String body) {
		var rest = mock(ElevenstMarketRestClient.class);
		when(rest.deleteRecorded("123")).thenReturn(new ElevenstMarketRestClient.DeleteResponse(200, body, "trace-2"));
		assertThatThrownBy(() -> new ElevenstMarketClient(rest).deleteFromMarket("123"))
			.hasMessageContaining("삭제 미확인").hasMessageContaining("trace-2");
	}
}
