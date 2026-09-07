package com.sbshop.agent.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Reads a fixed worker status mount, never credentials, browser sessions or arbitrary files. */
@RestController
@RequestMapping("/api/v1/marketplus/observer")
public class MarketPlusObserverController {
	private final String statusPath;
	private final ObjectMapper mapper;
	private final Clock clock;
	private static final Map<String, String> ERRORS = Map.ofEntries(
		Map.entry("LOGIN_REQUIRED", "서버 수집 브라우저에서 카페24 로그인이 필요합니다."),
		Map.entry("HISTORY_PAGE_NOT_STABLE", "전송 이력 화면의 로딩·표시 형식을 확인하지 못했습니다. 저장한 페이지는 보존됩니다."),
		Map.entry("HISTORY_PAGE_NOT_VERIFIED", "전송 이력 화면을 확인하지 못했습니다. 미확인 부분은 수집 완료로 처리하지 않습니다."),
		Map.entry("HISTORY_DATE_RANGE_REVIEW_REQUIRED", "전송 이력의 조회 기간을 확인해야 합니다."),
		Map.entry("MALL_ACCOUNT_MISMATCH", "수집 브라우저의 쇼핑몰이 설정된 쇼핑몰과 달라 수집을 중지했습니다."),
		Map.entry("SELLER_ACCOUNT_MISMATCH", "수집 브라우저의 마켓 판매 계정이 설정과 달라 수집을 중지했습니다."),
		Map.entry("ACCOUNT_CONFIGURATION_REQUIRED", "자동 수집에 사용할 마켓 판매 계정 설정이 필요합니다."),
		Map.entry("ACCOUNT_PAGE_NOT_VERIFIED", "수집 브라우저의 쇼핑몰·연동 계정을 확인하지 못했습니다."),
		Map.entry("BROWSER_LOGIN_OR_COLLECTION_REQUIRED", "서버 수집 브라우저의 로그인 또는 이력 화면을 확인하세요."),
		Map.entry("COLLECTION_INTERRUPTED", "이력 수집이 중단되었습니다. 저장한 페이지는 보존됩니다."),
		Map.entry("ACCOUNT_NOT_READY", "카페24·G마켓·옥션의 이력 연결 설정을 확인하세요."),
		Map.entry("HTTP_401", "서버 저장 인증을 확인하세요."), Map.entry("HTTP_403", "서버 저장 권한을 확인하세요."),
		Map.entry("HTTP_400", "서버의 계정 설정과 수집 파일을 확인하세요."),
		Map.entry("HTTP_429", "요청 제한으로 서버 저장을 대기하고 있습니다."),
		Map.entry("ROW_STORAGE_RESULT_UNKNOWN", "일부 이력의 저장 결과가 미확인되어 재시도를 대기하고 있습니다."),
		Map.entry("CONNECTION_RESULT_UNKNOWN", "서버 저장 응답을 확인하지 못해 재시도를 대기하고 있습니다."));

	@Autowired
	public MarketPlusObserverController(@Value("${marketplus.observer-status-path:}")
	String statusPath, ObjectMapper mapper) {
		this(statusPath, mapper, Clock.systemUTC());
	}

	MarketPlusObserverController(String statusPath, ObjectMapper mapper, Clock clock) {
		this.statusPath = statusPath;
		this.mapper = mapper;
		this.clock = clock;
	}

	public record Status(String state, String message, boolean collectEnabled, boolean uploadEnabled,
		Instant heartbeatAt, Instant lastCollectedAt, Instant lastUploadedAt, Instant nextAttemptAt,
		int pendingFiles, int rejectedFiles, int blockedFiles) {
	}

	@GetMapping("/status")
	public Status status() {
		if (statusPath.isBlank())
			return unavailable("NOT_CONFIGURED", "마켓플러스 자동 수집이 연결되지 않았습니다.");
		try {
			Path path = Path.of(statusPath);
			if (!Files.isRegularFile(path) || Files.size(path) > 65_536)
				return unavailable("UNAVAILABLE", "자동 수집 상태를 읽을 수 없습니다.");
			JsonNode value = mapper.readTree(Files.readString(path));
			String state = value.path("state").asText();
			if (!Set.of("PAUSED", "RUNNING", "ATTENTION", "OBSERVED_PARTIAL").contains(state)
				|| !value.path("collectEnabled").isBoolean() || !value.path("uploadEnabled").isBoolean())
				throw new IllegalArgumentException();
			Instant heartbeat = instant(value.path("heartbeatAt"), false);
			if (heartbeat == null)
				throw new IllegalArgumentException();
			boolean stale = heartbeat.isBefore(clock.instant().minusSeconds(180));
			JsonNode upload = value.path("upload");
			String code = value.path("error").asText("");
			if (code.isBlank())
				code = upload.path("error").asText("");
			String message = switch (state) {
				case "PAUSED" -> "자동 수집과 서버 저장이 중지되어 있습니다.";
				case "RUNNING" -> "전송 이력 수집·서버 저장을 확인하고 있습니다.";
				case "ATTENTION" -> ERRORS.getOrDefault(code, "자동 수집 또는 서버 저장에 확인이 필요합니다. 미확인 이력을 성공으로 처리하지 않습니다.");
				default -> "수집한 기간·페이지의 이력입니다. 전체 상품의 최신 상태를 보장하지 않습니다.";
			};
			return new Status(stale ? "STALE" : state, stale ? "자동 수집기의 최근 동작을 확인하지 못했습니다." : message,
				value.path("collectEnabled").asBoolean(), value.path("uploadEnabled").asBoolean(), heartbeat,
				instant(value.path("lastCollectedAt"), false), instant(value.path("lastUploadedAt"), false),
				instant(upload.path("nextAttemptAt"), true),
				count(upload.path("pending")), count(upload.path("withRejections")), count(upload.path("blocked")));
		} catch (Exception ignored) {
			return unavailable("UNAVAILABLE", "자동 수집 상태를 읽을 수 없습니다.");
		}
	}

	private Instant instant(JsonNode value, boolean futureAllowed) {
		if (value.isMissingNode() || value.isNull() || value.isNumber() && value.asDouble() == 0)
			return null;
		if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0)
			throw new IllegalArgumentException();
		Instant result = Instant.ofEpochMilli((long)(value.asDouble() * 1000));
		if (!futureAllowed && result.isAfter(clock.instant().plusSeconds(300)))
			throw new IllegalArgumentException();
		return result;
	}

	private int count(JsonNode value) {
		if (value.isMissingNode() || value.isNull())
			return 0;
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0)
			throw new IllegalArgumentException();
		return value.asInt();
	}

	private static Status unavailable(String state, String message) {
		return new Status(state, message, false, false, null, null, null, null, 0, 0, 0);
	}
}
