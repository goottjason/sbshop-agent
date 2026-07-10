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

/**
 * 11번가 상품 API REST 클라이언트 (GET/POST/PUT, 원시 문자열 XML, {@link ElevenstProperties}에서 키 주입).
 * 주문 API용 {@link com.sbshop.agent.infrastructure.client.elevenst.ElevenstOrderRestClient}와 구분된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstMarketRestClient {

	private static final Charset EUC_KR = Charset.forName("EUC-KR");
	private final ElevenstProperties properties;
	// 자격증명 단일 소스: DB(sb_market_credential ELEVEN_STREET.accessKey) 우선, 없으면 env(ElevenstProperties) 폴백.
	private final MarketCredentialRepository marketCredentialRepository;

	private String resolveApiKey() {
		MarketCredential c = marketCredentialRepository.findByMarketType(MarketType.ELEVEN_STREET).orElse(null);
		return (c != null && c.getAccessKey() != null && !c.getAccessKey().isBlank())
			? c.getAccessKey() : properties.getApiKey();
	}

	public String get(String path) {
		return sendRequest(properties.getApiUrl() + path, "GET", null);
	}

	public String post(String path, String xmlBody) {
		return sendRequest(properties.getApiUrl() + path, "POST", xmlBody);
	}

	public String put(String path, String xmlBody) {
		return sendRequest(properties.getApiUrl() + path, "PUT", xmlBody);
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
}
