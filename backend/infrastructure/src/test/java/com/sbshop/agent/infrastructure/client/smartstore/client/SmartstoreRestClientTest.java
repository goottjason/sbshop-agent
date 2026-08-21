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

@ExtendWith(MockitoExtension.class)
class SmartstoreRestClientTest {

	@Mock
	private MarketCredentialRepository marketCredentialRepository;

	@Test
	@DisplayName("토큰 발급 form의 timestamp는 epoch 밀리초(13자리, >1e12)이고 서명 password와 동일 값이다")
	void tokenTimestampIsEpochMillisAndConsistentWithSignature() throws Exception {
		SmartstoreProperties properties = new SmartstoreProperties();
		properties.setClientId("CID");
		properties.setClientSecret("$2a$10$abcdefghijklmnopqrstuv");
		properties.setApiUrl("https://api.example.com/external");

		when(marketCredentialRepository.findByMarketType(MarketType.SMART_STORE))
			.thenReturn(Optional.empty());

		SmartstoreRestClient client = new SmartstoreRestClient(properties, new ObjectMapper(),
			marketCredentialRepository);

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

		String ts = capturedTimestamp[0];
		assertThat(ts).isNotNull();
		assertThat(ts.length())
			.as("timestamp must be epoch millis (>=13 digits), not epoch seconds (10 digits): %s", ts)
			.isGreaterThanOrEqualTo(13);
		assertThat(Long.parseLong(ts)).isGreaterThan(1_000_000_000_000L);
	}

	private static RequestMatcher captureFormTimestamp(String[] sink) {
		return request -> {
			String body = ((MockClientHttpRequest)request).getBodyAsString();
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
