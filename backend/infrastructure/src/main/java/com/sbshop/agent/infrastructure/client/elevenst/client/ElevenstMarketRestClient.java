package com.sbshop.agent.infrastructure.client.elevenst.client;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.elevenst.config.ElevenstProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstMarketRestClient {

	private static final Charset EUC_KR = Charset.forName("EUC-KR");
	private static final okhttp3.OkHttpClient REVIEWED_MUTATIONS = new okhttp3.OkHttpClient.Builder()
		.retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
		.connectTimeout(java.time.Duration.ofSeconds(10)).readTimeout(java.time.Duration.ofSeconds(30))
		.callTimeout(java.time.Duration.ofSeconds(45)).build();

	private final ElevenstProperties properties;
	private final MarketCredentialRepository marketCredentialRepository;

	public String get(String path) {
		return sendRequest(properties.getApiUrl() + path, "GET", null);
	}

	public String post(String path, String xmlBody) {
		return sendRequest(properties.getApiUrl() + path, "POST", xmlBody);
	}

	public String put(String path, String xmlBody) {
		return sendRequest(properties.getApiUrl() + path, "PUT", xmlBody);
	}

	public String delete(String path) {
		return sendRequest(properties.getApiUrl() + path, "DELETE", null);
	}

	public String accountReference() {
		return com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.account("ELEVEN_STREET",
			resolveApiKey());
	}

	/** 11st's price GET mutates data. Never use HttpURLConnection's ordinary GET retry behavior here. */
	public String mutatePriceOnce(String path, String expectedAccount, Runnable beforeWrite) {
		if (path == null || !path.matches("/rest/prodservices/product/price/[1-9][0-9]{0,17}/[1-9][0-9]{0,9}"))
			throw new IllegalArgumentException("11번가 가격 전용 변경 경로가 올바르지 않습니다.");
		return mutateOnce("GET", path, null, expectedAccount, beforeWrite);
	}

	public String mutateStockOnce(String path, String body, String expectedAccount, Runnable beforeWrite) {
		if (path == null || !(path.matches("/rest/prodservices/stockqty/[1-9][0-9]{0,17}")
			|| path.matches("/rest/prodstatservice/stat/(?:stopdisplay|restartdisplay)/[1-9][0-9]{0,17}")))
			throw new IllegalArgumentException("11번가 수량·판매 상태 전용 변경 경로가 올바르지 않습니다.");
		return mutateOnce("PUT", path, body, expectedAccount, beforeWrite);
	}

	private String mutateOnce(String method, String path, String body, String expectedAccount, Runnable beforeWrite) {
		java.util.Objects.requireNonNull(beforeWrite, "전송 직전 확인이 필요합니다.");
		String apiKey = resolveApiKey();
		String actualAccount = com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.account("ELEVEN_STREET",
			apiKey);
		if (expectedAccount == null || !expectedAccount.equals(actualAccount))
			throw new UnsupportedOperationException("11번가 전송 계정이 승인한 계정과 다릅니다.");
		var sent = new java.util.concurrent.atomic.AtomicBoolean();
		var client = REVIEWED_MUTATIONS.newBuilder().addNetworkInterceptor(chain -> {
			if (!sent.compareAndSet(false, true))
				throw new IOException("11번가 변경 요청의 자동 재전송을 차단했습니다.");
			if (!expectedAccount.equals(accountReference()))
				throw new UnsupportedOperationException("11번가 전송 계정이 변경되었습니다.");
			beforeWrite.run();
			if (!expectedAccount.equals(accountReference()))
				throw new UnsupportedOperationException("전송 직전 확인 중 11번가 계정이 변경되었습니다.");
			var response = chain.proceed(chain.request());
			// OkHttp can follow 503 Retry-After:0 even with connection retries disabled.
			// Reject every non-success status here before any automatic follow-up can observe it.
			if (response.code() >= 300) {
				try (response) {
					var headers = new org.springframework.http.HttpHeaders();
					response.headers().toMultimap().forEach(headers::put);
					throw new org.springframework.web.client.RestClientResponseException("11번가 HTTP " + response.code(),
						response.code(), "", headers, priceBytes(response), EUC_KR);
				}
			}
			return response;
		}).build();
		var requestBody = method.equals("GET") ? null : okhttp3.RequestBody.create(
			body == null ? new byte[0] : body.getBytes(EUC_KR), okhttp3.MediaType.get("text/xml; charset=EUC-KR"));
		var request = new okhttp3.Request.Builder().url(properties.getApiUrl() + path).method(method, requestBody)
			.header("openapikey", apiKey).header("Content-Type", "text/xml; charset=EUC-KR")
			.header("Cache-Control", "no-store").build();
		try (var response = client.newCall(request).execute()) {
			byte[] bytes = priceBytes(response);
			if (bytes.length == 0)
				throw new IllegalStateException("11번가 변경 응답이 비어 있습니다. 재조회가 필요합니다.");
			return new String(bytes, EUC_KR);
		} catch (IOException transport) {
			throw new org.springframework.web.client.ResourceAccessException("11번가 변경 응답을 확인하지 못했습니다. 재조회가 필요합니다.",
				transport);
		}
	}

	private static byte[] priceBytes(okhttp3.Response response) throws IOException {
		if (response.body() == null)
			return new byte[0];
		byte[] bytes = response.body().byteStream().readNBytes(2_000_001);
		if (bytes.length > 2_000_000)
			throw new IllegalStateException("11번가 변경 응답 크기가 처리 범위를 초과했습니다.");
		return bytes;
	}

	public String requestStrict(String method, String path, String body) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection)URI.create(properties.getApiUrl() + path).toURL().openConnection();
			conn.setInstanceFollowRedirects(false);
			conn.setUseCaches(false);
			conn.setRequestMethod(method);
			conn.setRequestProperty("openapikey", resolveApiKey());
			conn.setRequestProperty("Content-Type", "text/xml; charset=EUC-KR");
			conn.setRequestProperty("Cache-Control", "no-cache, no-store");
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(30000);
			if (body != null && !body.isEmpty()) {
				conn.setDoOutput(true);
				try (OutputStream out = conn.getOutputStream()) {
					out.write(body.getBytes(EUC_KR));
				}
			}
			int code = conn.getResponseCode();
			byte[] bytes;
			try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
				bytes = in == null ? new byte[0] : in.readAllBytes();
			}
			if (code >= 300) {
				var headers = new org.springframework.http.HttpHeaders();
				conn.getHeaderFields().forEach((key, values) -> {
					if (key != null)
						headers.put(key, values);
				});
				throw new org.springframework.web.client.RestClientResponseException("11번가 HTTP " + code, code, "",
					headers, bytes, EUC_KR);
			}
			if (bytes.length == 0)
				throw new IllegalStateException("11번가 빈 응답");
			return new String(bytes, EUC_KR);
		} catch (IOException e) {
			throw new org.springframework.web.client.ResourceAccessException("11번가 응답 확인 실패", e);
		} finally {
			if (conn != null)
				conn.disconnect();
		}
	}

	private String sendRequest(String urlStr, String method, String body) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection)URI.create(urlStr).toURL().openConnection();
			conn.setRequestMethod(method);
			conn.setRequestProperty("openapikey", resolveApiKey());
			conn.setRequestProperty("Content-Type", "text/xml; charset=EUC-KR");
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(30000);

			if (body != null && !body.isEmpty()) {
				conn.setDoOutput(true);
				try (OutputStream os = conn.getOutputStream()) {
					os.write(body.getBytes(EUC_KR));
				}
			}

			int responseCode = conn.getResponseCode();
			InputStream is = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
			if (is == null)
				return "<resultCode>ERROR</resultCode><message>NO_RESPONSE</message>";
			return new String(is.readAllBytes(), EUC_KR);
		} catch (IOException e) {
			log.error("[Elevenst {} Error] url: {}, msg: {}", method, urlStr, e.getMessage());
			return "<resultCode>ERROR</resultCode><message>" + e.getMessage() + "</message>";
		} finally {
			if (conn != null)
				conn.disconnect();
		}
	}

	private String resolveApiKey() {
		MarketCredential c = marketCredentialRepository.findByMarketType(MarketType.ELEVEN_STREET).orElse(null);
		return (c != null && c.getAccessKey() != null && !c.getAccessKey().isBlank())
			? c.getAccessKey() : properties.getApiKey();
	}
}
