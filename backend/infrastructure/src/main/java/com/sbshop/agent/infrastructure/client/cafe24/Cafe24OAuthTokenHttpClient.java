package com.sbshop.agent.infrastructure.client.cafe24;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class Cafe24OAuthTokenHttpClient implements Cafe24OAuthTokenClient {

	private final RestClient restClient;

	public Cafe24OAuthTokenHttpClient(RestClient.Builder builder) {
		this.restClient = builder.build();
	}

	@Override
	public TokenResponse exchange(String mallId, String clientId, String clientSecret,
		String formPayload) {
		String authHeader = "Basic " + Base64.getEncoder()
			.encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
		String url = "https://" + mallId + ".cafe24api.com/api/v2/oauth/token";

		JsonNode response = restClient.post()
			.uri(url)
			.header(HttpHeaders.AUTHORIZATION, authHeader)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(formPayload)
			.retrieve()
			.onStatus(
				status -> status.is4xxClientError() || status.is5xxServerError(),
				(req, resp) -> {
					String errorBody =
						new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
					throw new RuntimeException("Cafe24 API Error: " + errorBody);
				})
			.body(JsonNode.class);

		if (response == null || !response.has("access_token")) {
			throw new RuntimeException("Cafe24 토큰 응답에 access_token이 없습니다");
		}
		String accessToken = response.get("access_token").asText();
		String refreshToken = response.get("refresh_token").asText();
		String expiresAtStr = response.get("expires_at").asText().replace(" ", "T");
		var expiresAt = LocalDateTime.parse(expiresAtStr)
			.atZone(ZoneId.of("Asia/Seoul")).toInstant();
		return new TokenResponse(accessToken, refreshToken, expiresAt);
	}
}
