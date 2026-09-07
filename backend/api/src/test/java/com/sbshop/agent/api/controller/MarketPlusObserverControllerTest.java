package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketPlusObserverControllerTest {
	@TempDir
	Path root;
	final ObjectMapper mapper = new ObjectMapper();
	final Clock clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);

	String body(String state, long heartbeat) {
		return "{\"state\":\"" + state + "\",\"collectEnabled\":true,\"uploadEnabled\":true,\"heartbeatAt\":"
			+ heartbeat + ",\"lastCollectedAt\":1788695900}";
	}

	MarketPlusObserverController controller(String json) throws Exception {
		Path path = root.resolve("status.json");
		Files.writeString(path, json);
		return new MarketPlusObserverController(path.toString(), mapper, clock);
	}

	@Test
	void missingConfigurationAndMissingFileNeverReportRunning() {
		assertThat(new MarketPlusObserverController("", mapper, clock).status().state()).isEqualTo("NOT_CONFIGURED");
		assertThat(new MarketPlusObserverController(root.resolve("missing").toString(), mapper, clock).status().state())
			.isEqualTo("UNAVAILABLE");
	}

	@Test
	void healthyHeartbeatStillIndicatesPartialCoverage() throws Exception {
		var status = controller(body("OBSERVED_PARTIAL", clock.instant().getEpochSecond())).status();
		assertThat(status.state()).isEqualTo("OBSERVED_PARTIAL");
		assertThat(status.message()).contains("전체 상품의 최신 상태를 보장하지 않습니다");
		assertThat(status.lastUploadedAt()).isNull();
	}

	@Test
	void staleProcessNeverKeepsOldRunningLabel() throws Exception {
		var status = controller(body("RUNNING", clock.instant().minusSeconds(181).getEpochSecond())).status();
		assertThat(status.state()).isEqualTo("STALE");
		assertThat(status.message()).contains("최근 동작");
	}

	@Test
	void malformedUnknownAndOversizedStatusFailExplicitly() throws Exception {
		for (String json : new String[] {"invalid", "{}", body("SUCCESS", clock.instant().getEpochSecond()),
			" ".repeat(65537)})
			assertThat(controller(json).status().state()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void retryAfterAndCountsAreShownWithoutRawErrorOrSessionData() throws Exception {
		String json = body("ATTENTION", clock.instant().getEpochSecond()).replace("}",
			",\"sessionId\":\"private-session\",\"authorization\":\"private-token\",\"upload\":{\"error\":\"HTTP_429\",\"pending\":2,\"nextAttemptAt\":"
				+ clock.instant().plusSeconds(3600).getEpochSecond() + "}}");
		var status = controller(json).status();
		assertThat(status.message()).contains("요청 제한");
		assertThat(status.pendingFiles()).isEqualTo(2);
		assertThat(status.nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(3600));
		assertThat(status.toString()).doesNotContain("private-");
	}

	@Test
	void pageFailureIsDistinguishedFromLoginAndAccountMismatch() throws Exception {
		for (var entry : java.util.Map.of("LOGIN_REQUIRED", "로그인이 필요", "HISTORY_PAGE_NOT_STABLE", "로딩·표시 형식",
			"SELLER_ACCOUNT_MISMATCH", "판매 계정이 설정과 달라", "MALL_ACCOUNT_MISMATCH", "쇼핑몰이 설정된 쇼핑몰과 달라").entrySet()) {
			String json = body("ATTENTION", clock.instant().getEpochSecond()).replace("}",
				",\"error\":\"" + entry.getKey() + "\"}");
			assertThat(controller(json).status().message()).contains(entry.getValue());
		}
	}

	@Test
	void futureHeartbeatCannotConcealDeadProcess() throws Exception {
		assertThat(controller(body("RUNNING", clock.instant().plusSeconds(301).getEpochSecond())).status().state())
			.isEqualTo("UNAVAILABLE");
	}
}
