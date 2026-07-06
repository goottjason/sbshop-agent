package com.sbshop.agent.infrastructure.client.coupang.client;

import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangRestClient {

	private final CoupangProperties properties;
	private final RestClient restClient = RestClient.create();

	public String get(String path) {
		return request("GET", path, null);
	}

	public String put(String path, Object body) {
		return request("PUT", path, body);
	}

	public String post(String path, Object body) {
		return request("POST", path, body);
	}

	public void delete(String path) {
		request("DELETE", path, null);
	}

	public String requestWithBody(String method, String path, Object body) {
		return request(method, path, body);
	}

	private String request(String method, String path, Object body) {
		try {
			String datetime = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
					.format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
			String signature = generateSignature(method, path, datetime);

			var requestSpec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
					.uri(properties.getApiUrl() + path)
					.header(HttpHeaders.AUTHORIZATION,
							"CEA algorithm=HmacSHA256, access-key=" + properties.getAccessKey()
									+ ", signed-date=" + datetime + ", signature=" + signature)
					.header("X-Requested-By", properties.getVendorId())
					.header("signed-date", datetime);

			if (body != null) {
				requestSpec.contentType(MediaType.APPLICATION_JSON).body(body);
			}

			return requestSpec.retrieve().body(String.class);
		} catch (Exception e) {
			log.error("[Coupang {} Error] path: {}, msg: {}", method, path, e.getMessage());
			throw new RuntimeException("Coupang API 호출 실패", e);
		}
	}

	private String generateSignature(String method, String path, String datetime) throws Exception {
		String message = datetime + method + path;
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(properties.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(hash);
	}
}
