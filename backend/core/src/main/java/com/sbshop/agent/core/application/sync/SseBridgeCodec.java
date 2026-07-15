package com.sbshop.agent.core.application.sync;

import com.sbshop.agent.core.domain.order.enums.MarketType;

import java.nio.charset.StandardCharsets;

/**
 * worker→api SSE 브리지 페이로드 코덱(F-MISC-16).
 *
 * <p>worker JVM 스케줄러가 발행하는 {@code SyncCompletedEvent}는 api JVM의 SSE 리스너에 닿지 않는다.
 * 두 JVM은 공유 Postgres만 공유하므로 LISTEN/NOTIFY로 브리지한다. 이 클래스는 그 페이로드의
 * 직렬화/역직렬화만 담당하는 순수 함수 모음이다(테스트 가능한 조각 분리).
 *
 * <p>포맷: {@code marketType|success|errorMessage} (파이프 구분). errorMessage의 개행·파이프는
 * 파싱을 깨지 않도록 공백으로 치환하고, NOTIFY 페이로드 8000바이트 한계를 넘지 않도록 7000바이트로
 * 자른다. Batch 이벤트는 api JVM에서 이미 발행되므로 브리지 대상이 아니다(범위 밖).
 */
public final class SseBridgeCodec {

	/** LISTEN/NOTIFY 채널명. worker(NOTIFY)와 api(LISTEN)가 동일 상수를 써야 한다. */
	public static final String CHANNEL = "sbshop_sse";

	private static final char DELIMITER = '|';
	private static final int MAX_PAYLOAD_BYTES = 7000;

	private SseBridgeCodec() {}

	/** 역직렬화 결과. errorMessage는 성공 시 빈 문자열. */
	public record Parsed(MarketType marketType, boolean success, String errorMessage) {}

	/**
	 * 이벤트를 NOTIFY 페이로드 문자열로 직렬화한다. errorMessage의 개행·파이프는 공백으로 치환하고
	 * 전체 페이로드가 7000바이트를 넘으면 errorMessage 끝을 잘라 맞춘다.
	 */
	public static String serialize(MarketType marketType, boolean success, String errorMessage) {
		String sanitized = sanitize(errorMessage);
		String payload = marketType.name() + DELIMITER + success + DELIMITER + sanitized;

		if (payload.getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES) {
			return payload;
		}

		// errorMessage 부분만 바이트 단위로 잘라 한계에 맞춘다(멀티바이트 문자 경계 보존).
		String prefix = marketType.name() + DELIMITER + success + DELIMITER;
		int budget = MAX_PAYLOAD_BYTES - prefix.getBytes(StandardCharsets.UTF_8).length;
		return prefix + truncateToBytes(sanitized, Math.max(budget, 0));
	}

	/**
	 * NOTIFY 페이로드를 파싱한다. null·공백·필드 부족 등 형식이 깨진 페이로드는 null을 반환한다.
	 * 알 수 없는 marketType은 {@link MarketType#UNKNOWN}으로 대체한다.
	 */
	public static Parsed parse(String payload) {
		if (payload == null || payload.isBlank()) {
			return null;
		}

		// errorMessage 안의 파이프는 직렬화 시 제거되므로 앞의 두 구분자만 기준으로 분할한다.
		int first = payload.indexOf(DELIMITER);
		int second = payload.indexOf(DELIMITER, first + 1);
		if (first < 0 || second < 0) {
			return null;
		}

		MarketType marketType = parseMarketType(payload.substring(0, first));
		boolean success = Boolean.parseBoolean(payload.substring(first + 1, second));
		String errorMessage = payload.substring(second + 1);

		return new Parsed(marketType, success, errorMessage);
	}

	private static String sanitize(String errorMessage) {
		if (errorMessage == null) {
			return "";
		}
		return errorMessage.replace('\n', ' ').replace('\r', ' ').replace(DELIMITER, ' ');
	}

	private static MarketType parseMarketType(String name) {
		try {
			return MarketType.valueOf(name);
		} catch (IllegalArgumentException e) {
			return MarketType.UNKNOWN;
		}
	}

	private static String truncateToBytes(String value, int maxBytes) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length <= maxBytes) {
			return value;
		}
		int end = maxBytes;
		// UTF-8 연속 바이트(10xxxxxx) 중간에서 자르지 않도록 문자 경계까지 뒤로 물린다.
		while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
			end--;
		}
		return new String(bytes, 0, end, StandardCharsets.UTF_8);
	}
}
