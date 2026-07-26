package com.sbshop.agent.infrastructure.client.demand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.KeywordVolume;
import com.sbshop.agent.core.application.sourcing.port.KeywordVolumePort;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 네이버 검색광고 API 키워드도구 클라이언트 — 월간 검색량과 연관 키워드.
 *
 * <p>{@code GET /keywordstool?hintKeywords=…&showDetail=1} 은 시드 키워드의 연관 키워드까지
 * 월간 PC/모바일 검색량과 함께 돌려준다. 이 연관 키워드 목록은 스코어링(수요)뿐 아니라
 * 마켓 검색 키워드 생성(S4)에도 그대로 쓴다 — 실제 검색되는 말이라는 근거가 있는 키워드다.
 *
 * <p>인증: {@code X-Signature = Base64(HmacSHA256(secretKey, timestamp + "." + method + "." + path))}.
 * 서명 대상 경로에 쿼리스트링을 포함하면 401이 난다 — 경로만 넣어야 한다.
 *
 * <p>자격증명이 없으면 {@link #isEnabled()}가 false이고, 스코어링이 검색량 가중치를 빼고 정규화한다.
 */
@Slf4j
@Component
public class NaverKeywordToolClient implements KeywordVolumePort {

	private static final String BASE_URL = "https://api.searchad.naver.com";
	private static final String PATH = "/keywordstool";
	private static final int MAX_RESULTS = 30;

	/** 검색량이 1,000회 미만이면 API가 실수 대신 "< 10" 문자열을 준다. */
	private static final int UNDER_TEN = 5;

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private final ObjectMapper objectMapper;
	private final String apiKey;
	private final String secretKey;
	private final String customerId;

	public NaverKeywordToolClient(ObjectMapper objectMapper,
		@Value("${naver.searchad.api-key:}") String apiKey,
		@Value("${naver.searchad.secret-key:}") String secretKey,
		@Value("${naver.searchad.customer-id:}") String customerId) {
		this.objectMapper = objectMapper;
		this.apiKey = apiKey;
		this.secretKey = secretKey;
		this.customerId = customerId;
	}

	@Override
	public boolean isEnabled() {
		return notBlank(apiKey) && notBlank(secretKey) && notBlank(customerId);
	}

	@Override
	public List<KeywordVolume> lookup(String seedKeyword) {
		if (!isEnabled() || seedKeyword == null || seedKeyword.isBlank())
			return List.of();

		// 키워드도구는 공백을 허용하지 않는다 — 붙여서 보내야 매칭된다.
		String hint = seedKeyword.replaceAll("\\s+", "");
		String url = BASE_URL + PATH + "?hintKeywords="
			+ URLEncoder.encode(hint, StandardCharsets.UTF_8) + "&showDetail=1";

		try {
			String timestamp = String.valueOf(System.currentTimeMillis());
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("X-Timestamp", timestamp)
				.header("X-API-KEY", apiKey)
				.header("X-Customer", customerId)
				.header("X-Signature", sign(timestamp, "GET", PATH))
				.GET()
				.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("[수요신호] 네이버 키워드도구 HTTP {} — keyword={}", response.statusCode(), hint);
				return List.of();
			}

			List<KeywordVolume> out = new ArrayList<>();
			for (JsonNode n : objectMapper.readTree(response.body()).path("keywordList")) {
				out.add(new KeywordVolume(
					n.path("relKeyword").asText(""),
					parseCount(n.path("monthlyPcQcCnt")),
					parseCount(n.path("monthlyMobileQcCnt")),
					n.path("compIdx").asText(null)));
				if (out.size() >= MAX_RESULTS)
					break;
			}
			return out;
		} catch (Exception e) {
			log.warn("[수요신호] 네이버 키워드도구 실패 keyword={}: {}", hint, e.getMessage());
			return List.of();
		}
	}

	/**
	 * 검색량 파싱. 10회 미만은 숫자가 아니라 {@code "< 10"} 문자열로 온다 —
	 * {@code asInt()}는 이걸 0으로 만들어버려서, 소량이라도 검색되는 키워드가 "검색량 0"이 된다.
	 */
	private int parseCount(JsonNode node) {
		if (node.isNumber())
			return node.asInt();
		String raw = node.asText("").trim();
		if (raw.isEmpty())
			return 0;
		if (raw.startsWith("<"))
			return UNDER_TEN;
		try {
			return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** 서명 대상은 "timestamp.METHOD.path" — 쿼리스트링을 넣으면 401이 난다. */
	private String sign(String timestamp, String method, String path) throws Exception {
		String message = timestamp + "." + method + "." + path;
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getEncoder()
			.encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
