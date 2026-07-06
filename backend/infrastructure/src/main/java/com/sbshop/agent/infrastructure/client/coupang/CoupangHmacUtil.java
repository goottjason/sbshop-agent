package com.sbshop.agent.infrastructure.client.coupang;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CoupangHmacUtil {

	private CoupangHmacUtil() {
	}

	public static String generateSignature(String method, String path, String accessKey, String secretKey) {
		String datetime = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
				.format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
		return buildAuth(method, path, "", datetime, accessKey, secretKey);
	}

	public static String generateSignatureUtc(String method, String url, String accessKey, String secretKey) {
		String path = url;
		String query = "";
		if (url.contains("?")) {
			String[] parts = url.split("\\?", 2);
			path = parts[0];
			query = parts[1];
		}
		String datetime = ZonedDateTime.now(ZoneId.of("UTC"))
				.format(DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'"));
		return buildAuth(method, path, query, datetime, accessKey, secretKey);
	}

	private static String buildAuth(String method, String path, String query,
			String datetime, String accessKey, String secretKey) {
		try {
			String message = datetime + method + path + query;
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
			String signature = HexFormat.of().formatHex(hash);
			return String.format("CEA algorithm=HmacSHA256, access-key=%s, signed-date=%s, signature=%s",
					accessKey, datetime, signature);
		} catch (Exception e) {
			throw new RuntimeException("Coupang HMAC 서명 생성 실패", e);
		}
	}

	public static String generateDatetime() {
		return ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
				.format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
	}
}
