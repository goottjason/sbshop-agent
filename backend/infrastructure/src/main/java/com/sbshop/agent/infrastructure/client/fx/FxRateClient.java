package com.sbshop.agent.infrastructure.client.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FxRateClient {

	private static final Duration TTL = Duration.ofHours(1);
	private static final String URL = "https://open.er-api.com/v6/latest/%s";

	private final ObjectMapper objectMapper;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public FxRateClient(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public BigDecimal toKrw(String currency) {
		String base = currency == null ? "" : currency.trim().toUpperCase();
		if (base.isEmpty()) {
			throw new IllegalStateException("통화를 알 수 없어 환산할 수 없다 — 가격 계산을 중단한다");
		}
		if ("KRW".equals(base)) {
			return BigDecimal.ONE;
		}
		Cached hit = cache.get(base);
		if (hit != null && !hit.isExpired()) {
			return hit.rate();
		}
		BigDecimal rate = fetch(base);
		if (rate == null || rate.signum() <= 0) {
			throw new IllegalStateException("환율을 가져오지 못했다: " + base);
		}
		cache.put(base, new Cached(rate, System.currentTimeMillis()));
		return rate;
	}

	protected BigDecimal fetch(String base) {
		try {
			HttpResponse<String> res = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(String.format(URL, base)))
					.timeout(Duration.ofSeconds(12)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			JsonNode body = objectMapper.readTree(res.body());
			if (!"success".equals(body.path("result").asText())) {
				throw new IllegalStateException("환율 API 실패(" + base + "): "
					+ body.path("error-type").asText(body.path("result").asText()));
			}
			JsonNode krw = body.path("rates").path("KRW");
			if (!krw.isNumber()) {
				throw new IllegalStateException("환율 응답에 KRW 가 없다: " + base);
			}
			return BigDecimal.valueOf(krw.asDouble());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("환율 조회 중단: " + base, e);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("환율 조회 실패: " + base, e);
		}
	}

	private record Cached(BigDecimal rate, long at) {
		boolean isExpired() {
			return System.currentTimeMillis() - at > TTL.toMillis();
		}
	}
}
