package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

class Cafe24OAuthTokenHttpClientTest {

	@Test
	@DisplayName("Cafe24 토큰 응답 JSON을 TokenResponse로 매핑한다")
	void mapsResponseToTokenResponse() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://mymall.cafe24api.com/api/v2/oauth/token"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION,
				"Basic " + Base64.getEncoder()
					.encodeToString("CID:SECRET".getBytes(StandardCharsets.UTF_8))))
			.andRespond(withSuccess(
				"{\"access_token\":\"AT1\",\"refresh_token\":\"RT2\","
					+ "\"expires_at\":\"2026-07-11 15:00:00\"}",
				MediaType.APPLICATION_JSON));

		var client = new Cafe24OAuthTokenHttpClient(builder);
		var resp = client.exchange("mymall", "CID", "SECRET",
			"grant_type=refresh_token&refresh_token=RT1");

		assertThat(resp.accessToken()).isEqualTo("AT1");
		assertThat(resp.refreshToken()).isEqualTo("RT2");
		assertThat(resp.expiresAt())
			.isEqualTo(java.time.LocalDateTime.of(2026, 7, 11, 15, 0, 0)
				.atZone(ZoneId.of("Asia/Seoul")).toInstant());
		server.verify();
	}
}
