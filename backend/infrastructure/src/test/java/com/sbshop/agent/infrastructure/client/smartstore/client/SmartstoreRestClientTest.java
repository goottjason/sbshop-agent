package com.sbshop.agent.infrastructure.client.smartstore.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.smartstore.config.SmartstoreProperties;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

/**
 * 회귀 가드: 스마트스토어 OAuth2 토큰 발급의 timestamp 는 반드시 epoch 밀리초(13자리)여야 한다.
 * epoch 초(10자리)를 보내면 Naver Commerce 가 유효창 밖으로 판정해 400 "timestamp 유효시간 만료"로 실패한다.
 */
@ExtendWith(MockitoExtension.class)
class SmartstoreRestClientTest {

	@Mock
	private MarketCredentialRepository marketCredentialRepository;

	@Test
	@DisplayName("토큰 발급 form의 timestamp는 epoch 밀리초(13자리, >1e12)이고 서명 password와 동일 값이다")
	void tokenTimestampIsEpochMillisAndConsistentWithSignature() throws Exception {
		SmartstoreProperties properties = new SmartstoreProperties();
		properties.setClientId("CID");
		// BCrypt.hashpw 는 유효한 salt 를 요구하므로 실제 bcrypt salt 를 secret 으로 사용.
		properties.setClientSecret("$2a$10$abcdefghijklmnopqrstuv");
		properties.setApiUrl("https://api.example.com/external");

		when(marketCredentialRepository.findByMarketType(MarketType.SMART_STORE))
			.thenReturn(Optional.empty());

		SmartstoreRestClient client =
			new SmartstoreRestClient(properties, new ObjectMapper(), marketCredentialRepository);

		// private final restClient 필드를 MockRestServiceServer 바인딩 인스턴스로 교체
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		String[] capturedTimestamp = new String[1];
		server.expect(requestTo("https://api.example.com/external/v1/oauth2/token"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(captureFormTimestamp(capturedTimestamp))
			.andRespond(withSuccess(
				"{\"access_token\":\"tok\",\"expires_in\":10800}", MediaType.APPLICATION_JSON));

		injectRestClient(client, builder.build());

		String token = client.getValidAccessToken();

		assertThat(token).isEqualTo("tok");
		server.verify();

		// 핵심 회귀 가드: 밀리초여야 한다 (13자리 이상, >1e12) — 초(10자리)는 거부.
		String ts = capturedTimestamp[0];
		assertThat(ts).isNotNull();
		assertThat(ts.length())
			.as("timestamp must be epoch millis (>=13 digits), not epoch seconds (10 digits): %s", ts)
			.isGreaterThanOrEqualTo(13);
		assertThat(Long.parseLong(ts)).isGreaterThan(1_000_000_000_000L);
	}

	/** 폼 바디에서 timestamp 필드 값을 추출해 캡처하는 RequestMatcher. */
	private static RequestMatcher captureFormTimestamp(String[] sink) {
		return request -> {
			String body = ((MockClientHttpRequest) request).getBodyAsString();
			for (String pair : body.split("&")) {
				if (pair.startsWith("timestamp=")) {
					sink[0] = pair.substring("timestamp=".length());
				}
			}
		};
	}

	private static void injectRestClient(SmartstoreRestClient client, RestClient restClient)
		throws Exception {
		Field f = SmartstoreRestClient.class.getDeclaredField("restClient");
		f.setAccessible(true);
		f.set(client, restClient);
	}
}
