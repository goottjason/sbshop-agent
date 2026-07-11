# SP-A: 마켓 연동·인증 정상화 (Cafe24 토큰) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 이 프로젝트에서는 실제 실행을 `sbshop-normalize` TDD 하네스로 인계하는 것을 권장한다(재현 테스트 Red→Green→검증 게이트).

**Goal:** api·worker 두 JVM 간 Cafe24 refresh_token 회전 경쟁으로 발생하는 `invalid_access_token`을 DB 단일 진실원 + Postgres advisory lock으로 근본 해소하고, 설정 페이지의 죽은 ESM+ 섹션을 제거한다.

**Architecture:** 토큰 상태(access_token·expiry·refresh_token) 3종을 `sb_market_credential`에 저장해 DB를 단일 진실원으로 삼는다. `getValidAccessToken()`은 DB에서 유효성을 판정하고, 만료 시에만 `TokenRefreshLock`(Postgres advisory lock)으로 프로세스 간 상호배제 후 double-check → refresh 1회 → 3종 저장한다. HTTP 토큰 교환은 `Cafe24OAuthTokenClient` 시임으로 분리해 로컬 Docker 없이 단위 테스트한다.

**Tech Stack:** Java 21, Spring Boot 3.5(멀티모듈 core/infrastructure/api/worker), Spring Data JPA, JdbcTemplate(PostgreSQL advisory lock), React 19/Vite/TS(frontend), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- 스키마 수동 관리(Flyway 제거). **DDL 변경 금지** — `access_token`(varchar 1000)·`token_expires_at`(timestamp) 컬럼은 `sb_market_credential`에 이미 존재(`MarketCredential.java:71-76`).
- 포트-인-코어 헥사고날 관례 준수: 인터페이스(포트)는 `core`, 구현체는 `infrastructure`.
- 검증 게이트: `:core:test`, `:infrastructure:test`, `:api:test` 통과 + 프론트 `tsc -p tsconfig.app.json` 0, `npm run build` 0.
- 프로세스 간 공유 상태는 인메모리 금지 — DB + advisory lock으로 조율(2 JVM 배포).
- advisory lock key는 **모든 프로세스에서 동일한 상수**여야 한다: `0xCAFE24L` (= 13291556).
- 토큰 만료 여유(skew): 5분(300초).
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: `TokenRefreshLock` 포트 + Postgres advisory lock 구현

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/domain/market/TokenRefreshLock.java`
- Create: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/lock/PostgresAdvisoryTokenRefreshLock.java`
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/lock/PostgresAdvisoryTokenRefreshLockTest.java`

**Interfaces:**
- Produces: `TokenRefreshLock.runExclusively(long key, Supplier<T> action) : T` — 동일 key에 대해 한 번에 하나의 호출만 action을 실행(다중 JVM 포함). 구현체 `PostgresAdvisoryTokenRefreshLock`은 `@Component`, `@Transactional`.

- [ ] **Step 1: 포트 인터페이스 작성**

`TokenRefreshLock.java`:
```java
package com.sbshop.agent.core.domain.market;

import java.util.function.Supplier;

/**
 * 프로세스 간 상호배제로 임계 구역(토큰 갱신 등)을 직렬화하는 포트.
 * 동일 key에 대해 한 번에 하나의 호출만 action을 실행하도록 보장한다(다중 JVM 포함).
 */
public interface TokenRefreshLock {
	<T> T runExclusively(long key, Supplier<T> action);
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (Docker 불필요 — mock JdbcTemplate)**

`PostgresAdvisoryTokenRefreshLockTest.java`:
```java
package com.sbshop.agent.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class PostgresAdvisoryTokenRefreshLockTest {

	@Mock private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("advisory lock을 획득하고 action 결과를 반환한다")
	void acquiresLockThenRunsAction() {
		when(jdbcTemplate.queryForObject(any(String.class), eq(Object.class), eq(42L)))
			.thenReturn(0);
		var lock = new PostgresAdvisoryTokenRefreshLock(jdbcTemplate);

		String result = lock.runExclusively(42L, () -> "DONE");

		assertThat(result).isEqualTo("DONE");
		verify(jdbcTemplate)
			.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, 42L);
	}
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*PostgresAdvisoryTokenRefreshLockTest*'`
Expected: FAIL — `PostgresAdvisoryTokenRefreshLock` 클래스 없음(컴파일 에러).

- [ ] **Step 4: 구현 작성**

`PostgresAdvisoryTokenRefreshLock.java`:
```java
package com.sbshop.agent.infrastructure.lock;

import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres 트랜잭션 범위 advisory lock으로 임계 구역을 프로세스 간 직렬화한다.
 * 락은 트랜잭션 커밋/롤백 시 자동 해제되며, 다른 프로세스는 같은 key에서 블록된다.
 * action 내부의 JPA 저장은 이 트랜잭션에 참여하므로 refresh + 영속화가 원자적이다.
 */
@Component
public class PostgresAdvisoryTokenRefreshLock implements TokenRefreshLock {

	private final JdbcTemplate jdbcTemplate;

	public PostgresAdvisoryTokenRefreshLock(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional
	public <T> T runExclusively(long key, Supplier<T> action) {
		jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, key);
		return action.get();
	}
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*PostgresAdvisoryTokenRefreshLockTest*'`
Expected: PASS

> **참고(범위 밖, CI 검증):** 실제 두 커넥션 간 직렬화(cross-process)는 Testcontainers Postgres로만 검증 가능하다. 로컬 Docker-off 환경에서는 이 단위 테스트(SQL·반환값 계약)로 게이트하고, 실 락 동작은 Task 4의 fake-lock 동시성 테스트 + 서버/CI 통합으로 확인한다.

- [ ] **Step 6: 커밋**

```bash
cd /Users/jasonair/Projects/sbshop-agent
git add backend/core/src/main/java/com/sbshop/agent/core/domain/market/TokenRefreshLock.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/lock/PostgresAdvisoryTokenRefreshLock.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/lock/PostgresAdvisoryTokenRefreshLockTest.java
git commit -m "feat(SP-A): TokenRefreshLock 포트 + Postgres advisory lock 구현

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `Cafe24OAuthTokenClient` 시임 추출 (HTTP 토큰 교환 분리)

**Files:**
- Create: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OAuthTokenClient.java`
- Create: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OAuthTokenHttpClient.java`
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OAuthTokenHttpClientTest.java`

**Interfaces:**
- Consumes: 없음(신규 시임).
- Produces: `Cafe24OAuthTokenClient.exchange(String mallId, String clientId, String clientSecret, String formPayload) : TokenResponse` — `TokenResponse(String accessToken, String refreshToken, java.time.Instant expiresAt)`. 매핑 규칙: 요청 URL `https://{mallId}.cafe24api.com/api/v2/oauth/token`, Basic auth = base64(`clientId:clientSecret`), body = `formPayload`(form-urlencoded). 응답 JSON `access_token`/`refresh_token`/`expires_at`(KST `yyyy-MM-dd HH:mm:ss`)를 파싱, `expires_at`은 Asia/Seoul → Instant.

- [ ] **Step 1: 인터페이스 작성**

`Cafe24OAuthTokenClient.java`:
```java
package com.sbshop.agent.infrastructure.client.cafe24;

import java.time.Instant;

/** Cafe24 OAuth 토큰 교환 HTTP 호출 시임(테스트 대체 가능). */
public interface Cafe24OAuthTokenClient {

	/**
	 * @param mallId       Cafe24 Mall ID (MarketCredential.clientId)
	 * @param clientId     Cafe24 Client ID (MarketCredential.accessKey) — Basic auth 사용자
	 * @param clientSecret Cafe24 Client Secret (MarketCredential.secretKey)
	 * @param formPayload  grant_type=... 형태의 x-www-form-urlencoded 본문
	 */
	TokenResponse exchange(String mallId, String clientId, String clientSecret, String formPayload);

	record TokenResponse(String accessToken, String refreshToken, Instant expiresAt) {}
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (MockRestServiceServer, Docker 불필요)**

`Cafe24OAuthTokenHttpClientTest.java`:
```java
package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

class Cafe24OAuthTokenHttpClientTest {

	@Test
	@DisplayName("Cafe24 토큰 응답 JSON을 TokenResponse로 매핑한다")
	void mapsResponseToTokenResponse() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://mymall.cafe24api.com/api/v2/oauth/token"))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess(
				"{\"access_token\":\"AT1\",\"refresh_token\":\"RT2\","
					+ "\"expires_at\":\"2026-07-11 15:00:00\"}",
				MediaType.APPLICATION_JSON));

		var client = new Cafe24OAuthTokenHttpClient(builder);
		var resp = client.exchange("mymall", "CID", "SECRET",
			"grant_type=refresh_token&refresh_token=RT1");

		assertThat(resp.accessToken()).isEqualTo("AT1");
		assertThat(resp.refreshToken()).isEqualTo("RT2");
		assertThat(resp.expiresAt())
			.isEqualTo(java.time.LocalDateTime.of(2026, 7, 11, 15, 0, 0)
				.atZone(ZoneId.of("Asia/Seoul")).toInstant());
		server.verify();
	}
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24OAuthTokenHttpClientTest*'`
Expected: FAIL — `Cafe24OAuthTokenHttpClient` 없음.

- [ ] **Step 4: 구현 작성 (기존 `requestTokenToCafe24` 로직 이관)**

`Cafe24OAuthTokenHttpClient.java`:
```java
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
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24OAuthTokenHttpClientTest*'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OAuthTokenClient.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OAuthTokenHttpClient.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OAuthTokenHttpClientTest.java
git commit -m "feat(SP-A): Cafe24 OAuth 토큰 교환 HTTP 시임 추출

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `Cafe24TokenManager` 재설계 — DB 진실원 + 3종 저장 + startup no-refresh

**Files:**
- Modify: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManager.java`
- Modify(생성자 시그니처 반영): `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerFailFastTest.java`
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerTest.java`

**Interfaces:**
- Consumes: `Cafe24OAuthTokenClient`(Task 2), `TokenRefreshLock`(Task 1), `MarketCredentialRepository.findByMarketType(MarketType)`, `MarketCredentialRepository.save(...)`, `MarketCredential` setters(`setAccessToken`/`setRefreshToken`/`setTokenExpiresAt`).
- Produces(공개 계약 불변): `getValidAccessToken() : String`(만료/무효 시 `IllegalStateException`), `isRefreshTokenPresent() : boolean`, `getApiUrl() : String`, `issueInitialToken(String code)`, `generateAuthorizationUrl(MarketCredential)`. 생성자: `Cafe24TokenManager(MarketCredentialRepository, Cafe24OAuthTokenClient, TokenRefreshLock)`.

> **테스트 방식:** `MarketCredentialRepository`는 Mockito `@Mock`으로 두되, `findByMarketType`이 **하나의 공유 `MarketCredential` 인스턴스**를 반환하도록 스텁한다. `MarketCredential`은 가변(`@Setter`)이므로 `persist()`의 setter 호출이 그 인스턴스 상태를 그대로 반영하고, `save`는 검증용(`verify`)으로만 쓴다(no-op). fake lock은 아래 `DIRECT_LOCK`(즉시 실행)을 사용한다. Task 4는 동시성용 `SerializingLock`을 별도 파일에 정의한다.

- [ ] **Step 1: 유효 토큰 재사용 테스트 작성 (HTTP 0회)**

`Cafe24TokenManagerTest.java`:
```java
package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24TokenManagerTest {

	@Mock MarketCredentialRepository repo;
	@Mock Cafe24OAuthTokenClient tokenClient;

	/** action을 즉시 실행하는(직렬화만 흉내) fake lock. */
	static final TokenRefreshLock DIRECT_LOCK = new TokenRefreshLock() {
		@Override public <T> T runExclusively(long key, Supplier<T> action) {
			return action.get();
		}
	};

	private MarketCredential credential(String access, LocalDateTime expiresAt, String refresh) {
		MarketCredential c = MarketCredential.builder()
			.marketType(MarketType.CAFE24).clientId("mymall")
			.accessKey("CID").secretKey("SECRET").refreshToken(refresh)
			.redirectUri("https://cb").build();
		c.setAccessToken(access);
		c.setTokenExpiresAt(expiresAt);
		return c;
	}

	@Test
	@DisplayName("DB 토큰이 유효하면 refresh 없이 그대로 반환한다")
	void reusesValidToken() {
		MarketCredential c = credential("AT-VALID",
			LocalDateTime.now().plusHours(1), "RT1");
		when(repo.findByMarketType(any())).thenReturn(Optional.of(c));

		var manager = new Cafe24TokenManager(repo, tokenClient, DIRECT_LOCK);

		assertThat(manager.getValidAccessToken()).isEqualTo("AT-VALID");
		verify(tokenClient, never()).exchange(any(), any(), any(), any());
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24TokenManagerTest*'`
Expected: FAIL — 생성자 `Cafe24TokenManager(repo, tokenClient, lock)` 미존재(현재는 repo 1개).

- [ ] **Step 3: `Cafe24TokenManager` 재작성**

`Cafe24TokenManager.java` 전체 교체:
```java
package com.sbshop.agent.infrastructure.client.cafe24;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24TokenManager {

	private static final long CAFE24_TOKEN_LOCK_KEY = 0xCAFE24L; // 모든 프로세스 공통 상수
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MarketCredentialRepository marketCredentialRepository;
	private final Cafe24OAuthTokenClient tokenClient;
	private final TokenRefreshLock refreshLock;

	@PostConstruct
	public void init() {
		// startup 강제 refresh 폐지 — 불필요한 refresh_token 회전이 2 JVM 경쟁을 유발했음.
		MarketCredential c = getCredential();
		if (c == null || c.getClientId() == null || c.getSecretKey() == null) {
			log.warn("🚨 Cafe24 API 정보 미등록 — 설정 페이지에서 키를 입력하세요.");
		} else if (c.getRefreshToken() == null || c.getRefreshToken().isBlank()) {
			log.warn("🚨 Cafe24 재인증 필요 — 인증 URL: {}", generateAuthorizationUrl(c));
		} else {
			log.info("✅ Cafe24 자격증명 확인됨 — 토큰은 최초 사용 시 필요하면 갱신합니다.");
		}
	}

	private MarketCredential getCredential() {
		return marketCredentialRepository.findByMarketType(MarketType.CAFE24).orElse(null);
	}

	public boolean isRefreshTokenPresent() {
		MarketCredential c = getCredential();
		return c != null && c.getRefreshToken() != null && !c.getRefreshToken().isBlank();
	}

	public String getValidAccessToken() {
		MarketCredential credential = getCredential();
		if (credential == null) {
			throw new IllegalStateException("Cafe24 credential 미등록 — 재인증이 필요합니다");
		}
		if (isTokenValid(credential)) {
			return credential.getAccessToken();
		}
		String token = refreshLock.runExclusively(CAFE24_TOKEN_LOCK_KEY, () -> {
			MarketCredential fresh = getCredential(); // 락 획득 후 재조회(double-check)
			if (fresh != null && isTokenValid(fresh)) {
				return fresh.getAccessToken(); // 다른 프로세스가 이미 갱신함 — HTTP 생략
			}
			return doRefresh(fresh);
		});
		if (token == null) {
			throw new IllegalStateException(
				"Cafe24 access token 획득 실패 — 재인증이 필요합니다(refresh token 만료/무효 또는 미발급)");
		}
		return token;
	}

	private boolean isTokenValid(MarketCredential c) {
		if (c.getAccessToken() == null || c.getAccessToken().isBlank()
			|| c.getTokenExpiresAt() == null) {
			return false;
		}
		Instant expiry = c.getTokenExpiresAt().atZone(KST).toInstant();
		return expiry.minusSeconds(300).isAfter(Instant.now());
	}

	private String doRefresh(MarketCredential credential) {
		if (credential == null) {
			return null;
		}
		String refreshToken = credential.getRefreshToken();
		if (refreshToken == null || refreshToken.isBlank()) {
			return null;
		}
		try {
			var resp = tokenClient.exchange(
				credential.getClientId(), credential.getAccessKey(), credential.getSecretKey(),
				"grant_type=refresh_token&refresh_token=" + refreshToken);
			persist(credential, resp);
			log.info("✅ Cafe24 토큰 갱신 완료 (만료: {})", credential.getTokenExpiresAt());
			return resp.accessToken();
		} catch (Exception e) {
			log.error("❌ Cafe24 토큰 갱신 실패 — refresh token 만료/무효 추정", e);
			return null;
		}
	}

	private void persist(MarketCredential credential, Cafe24OAuthTokenClient.TokenResponse resp) {
		credential.setAccessToken(resp.accessToken());
		credential.setRefreshToken(resp.refreshToken());
		credential.setTokenExpiresAt(LocalDateTime.ofInstant(resp.expiresAt(), KST));
		marketCredentialRepository.save(credential);
	}

	public String getApiUrl() {
		MarketCredential credential = getCredential();
		if (credential == null) {
			return null;
		}
		return "https://" + credential.getClientId() + ".cafe24api.com/api/v2";
	}

	public String generateAuthorizationUrl(MarketCredential credential) {
		String apiUrl = "https://" + credential.getClientId() + ".cafe24api.com/api/v2";
		String scope = "mall.read_application,mall.write_application,"
			+ "mall.read_product,mall.write_product,"
			+ "mall.read_collection,mall.write_collection,"
			+ "mall.read_order,mall.write_order,"
			+ "mall.read_shipping,mall.write_shipping";
		return String.format(
			"%s/oauth/authorize?response_type=code&client_id=%s&state=shouldbeshopping&redirect_uri=%s&scope=%s",
			apiUrl, credential.getAccessKey(), credential.getRedirectUri(), scope);
	}

	public void issueInitialToken(String code) {
		MarketCredential credential = getCredential();
		if (credential == null) {
			throw new RuntimeException("Cafe24 credential not found in DB.");
		}
		String payload = String.format(
			"grant_type=authorization_code&code=%s&redirect_uri=%s",
			code, credential.getRedirectUri());
		var resp = tokenClient.exchange(
			credential.getClientId(), credential.getAccessKey(), credential.getSecretKey(), payload);
		persist(credential, resp);
		log.info("🎉 [최초 인증 성공] 토큰 3종이 발급·저장되었습니다.");
	}
}
```

- [ ] **Step 4: 유효 토큰 재사용 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24TokenManagerTest*'`
Expected: PASS

- [ ] **Step 5: 만료 시 refresh 1회 + 3종 저장 테스트 추가**

`Cafe24TokenManagerTest.java`에 추가:
```java
	@Test
	@DisplayName("만료 토큰이면 refresh 1회 호출 후 access/refresh/expiry 3종을 저장한다")
	void refreshesAndPersistsAllThree() {
		MarketCredential c = credential("AT-OLD",
			LocalDateTime.now().minusMinutes(1), "RT1"); // 만료
		when(repo.findByMarketType(any())).thenReturn(Optional.of(c));
		when(tokenClient.exchange(any(), any(), any(), any()))
			.thenReturn(new Cafe24OAuthTokenClient.TokenResponse(
				"AT-NEW", "RT2", Instant.now().plusSeconds(7200)));

		var manager = new Cafe24TokenManager(repo, tokenClient, DIRECT_LOCK);
		String token = manager.getValidAccessToken();

		assertThat(token).isEqualTo("AT-NEW");
		assertThat(c.getAccessToken()).isEqualTo("AT-NEW");
		assertThat(c.getRefreshToken()).isEqualTo("RT2");
		assertThat(c.getTokenExpiresAt()).isNotNull();
		verify(repo).save(c);
	}

	@Test
	@DisplayName("refresh token이 없어 토큰을 못 얻으면 IllegalStateException으로 즉시 실패한다")
	void failFastWhenNoToken() {
		MarketCredential c = credential(null, null, null); // access·refresh 모두 없음
		when(repo.findByMarketType(any())).thenReturn(Optional.of(c));

		var manager = new Cafe24TokenManager(repo, tokenClient, DIRECT_LOCK);

		org.assertj.core.api.Assertions.assertThatThrownBy(manager::getValidAccessToken)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("재인증");
	}
```

- [ ] **Step 6: 추가 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24TokenManagerTest*'`
Expected: PASS (3 tests)

- [ ] **Step 7: 기존 FailFast 테스트의 생성자 호출 갱신**

`Cafe24TokenManagerFailFastTest.java`의 `new Cafe24TokenManager(marketCredentialRepository)`를 새 생성자로 교체:
```java
	@Mock private com.sbshop.agent.infrastructure.client.cafe24.Cafe24OAuthTokenClient tokenClient;

	// ... 테스트 본문에서:
	Cafe24TokenManager manager = new Cafe24TokenManager(
		marketCredentialRepository, tokenClient,
		new com.sbshop.agent.core.domain.market.TokenRefreshLock() {
			@Override public <T> T runExclusively(long key, java.util.function.Supplier<T> a) {
				return a.get();
			}
		});
```
(기존 시나리오: `findByMarketType`가 `Optional.empty()` → `getValidAccessToken`이 credential null로 `IllegalStateException("...재인증...")`. 계약 유지됨.)

- [ ] **Step 8: infrastructure 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test`
Expected: PASS (기존 + 신규 모두)

- [ ] **Step 9: 커밋**

```bash
git add backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManager.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerTest.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerFailFastTest.java
git commit -m "feat(SP-A): Cafe24TokenManager DB 진실원화 + 3종 저장 + startup no-refresh

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 동시 refresh 단일화 검증 (double-check under lock)

**Files:**
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerConcurrencyTest.java`
- (구현 변경 없음 — Task 3의 double-check + lock이 이미 보장. 이 Task는 그 계약을 회귀 테스트로 고정.)

**Interfaces:**
- Consumes: Task 3의 `Cafe24TokenManager`, `TokenRefreshLock`.

- [ ] **Step 1: 동시성 재현 테스트 작성**

`Cafe24TokenManagerConcurrencyTest.java`:
```java
package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class Cafe24TokenManagerConcurrencyTest {

	/** JVM 내 ReentrantLock으로 프로세스 간 상호배제를 흉내내는 fake lock. */
	static class SerializingLock implements TokenRefreshLock {
		private final ReentrantLock lock = new ReentrantLock();
		@Override public <T> T runExclusively(long key, Supplier<T> action) {
			lock.lock();
			try { return action.get(); } finally { lock.unlock(); }
		}
	}

	@Test
	@DisplayName("N개 스레드가 동시에 만료 토큰을 갱신해도 refresh HTTP는 정확히 1회만 발생한다")
	void concurrentRefreshHappensOnce() throws Exception {
		MarketCredential c = MarketCredential.builder()
			.marketType(MarketType.CAFE24).clientId("mymall")
			.accessKey("CID").secretKey("SECRET").refreshToken("RT1")
			.redirectUri("https://cb").build();
		c.setAccessToken("AT-OLD");
		c.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1)); // 만료

		MarketCredentialRepository repo = Mockito.mock(MarketCredentialRepository.class);
		Mockito.when(repo.findByMarketType(MarketType.CAFE24)).thenReturn(Optional.of(c));

		AtomicInteger exchangeCalls = new AtomicInteger();
		Cafe24OAuthTokenClient client = (mallId, id, secret, payload) -> {
			exchangeCalls.incrementAndGet();
			return new Cafe24OAuthTokenClient.TokenResponse(
				"AT-NEW", "RT2", Instant.now().plusSeconds(7200));
		};

		var manager = new Cafe24TokenManager(repo, client, new SerializingLock());

		int threads = 12;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		var results = new java.util.concurrent.ConcurrentLinkedQueue<String>();
		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				ready.countDown();
				try { go.await(); } catch (InterruptedException ignored) {}
				results.add(manager.getValidAccessToken());
			});
		}
		ready.await();
		go.countDown();
		pool.shutdown();
		pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

		assertThat(exchangeCalls.get()).isEqualTo(1); // double-check로 단 1회
		assertThat(results).allMatch("AT-NEW"::equals);
		assertThat(results).hasSize(threads);
	}
}
```

- [ ] **Step 2: 테스트 통과 확인 (Task 3 구현으로 이미 Green — 회귀 고정)**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24TokenManagerConcurrencyTest*'`
Expected: PASS. 만약 FAIL(예: exchange 2회 이상)이면 Task 3의 `getValidAccessToken` double-check(락 내부 재조회) 로직 결함이므로 그 지점을 수정.

- [ ] **Step 3: 커밋**

```bash
git add backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerConcurrencyTest.java
git commit -m "test(SP-A): 동시 refresh 단일화(double-check) 회귀 테스트 고정

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 프론트 설정 페이지 — ESM+(GMARKET) 죽은 섹션 제거

**Files:**
- Modify: `frontend/src/pages/Settings.tsx` (탭 배열 `:84`, GMARKET 블록 `:318-348`)

**Interfaces:**
- 백엔드 계약 변경 없음. Cafe24 상태 배지는 기존 `GET /api/admin/sync/cafe24/status`의 `Cafe24Status.message`("...재인증을 진행하세요.")를 그대로 렌더 — 재인증 필요 문구는 이미 백엔드가 제공(`Cafe24AuthController.java:46,54`).

- [ ] **Step 1: 탭 배열에서 GMARKET 항목 제거**

`Settings.tsx:84` 라인 삭제:
```tsx
    { id: 'GMARKET', label: 'G마켓·옥션 (ESM+ 단일 로그인)' },
```
→ 삭제. 남는 탭: COUPANG / SMART_STORE / ELEVEN_STREET / CAFE24.

- [ ] **Step 2: GMARKET 폼 블록 제거**

`Settings.tsx:318-348`의 블록 전체 삭제:
```tsx
          {activeTab === 'GMARKET' && (
            <>
              ... (마스터 ID / 비밀번호 입력 폼) ...
            </>
          )}
```
→ 삭제. 바로 아래 저장 버튼 `<div style={{ marginTop: '24px' ...}}>`(`:350` 이하)는 유지.

- [ ] **Step 3: 타입 체크 통과 확인**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: 에러 0. (`activeTab` 초기값 `'COUPANG'`이므로 GMARKET 제거로 미도달 상태 없음. `formData.accessKey`/`secretKey`는 다른 탭에서도 사용 — 미참조 변수 경고 없음.)

- [ ] **Step 4: 빌드 통과 확인**

Run: `cd frontend && npm run build`
Expected: 성공(exit 0).

- [ ] **Step 5: 수동 스모크(선택) — 탭 렌더**

Run: `cd frontend && npm run dev` 후 설정 페이지에서 4개 탭만 노출되고 각 탭 저장 폼이 정상 렌더되는지 확인. Cafe24 탭 상태 배지에 정상/재인증 문구가 뜨는지 확인.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/pages/Settings.tsx
git commit -m "fix(SP-A): 설정 페이지 죽은 ESM+(G마켓·옥션 단일 로그인) 섹션 제거

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 통합 게이트 + 라이브 확인 준비

**Files:** 없음(검증 전용).

- [ ] **Step 1: 백엔드 전체 게이트**

Run: `cd backend && ./gradlew :core:test :infrastructure:test :api:test`
Expected: 전부 PASS. (로컬 Docker-off 시 testcontainers 컨텍스트 스모크는 initializationError로 스킵될 수 있음 — SP-A 변경과 무관, CI/서버에서 확인.)

- [ ] **Step 2: 프론트 게이트**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: tsc 0, build 0.

- [ ] **Step 3: 라이브 확인 체크리스트 문서화(배포 후, 사용자 허가 하)**

배포 직후 확인 항목(결과서/원장에 기록):
1. api·worker 두 JVM 기동 로그에 startup refresh 호출이 없다(`✅ Cafe24 자격증명 확인됨 — 토큰은 최초 사용 시...`).
2. 첫 주문 동기화 시 refresh가 한쪽에서만 1회 발생하고, 이후 두 JVM이 같은 토큰을 공유한다.
3. G마켓/옥션 스케줄러(10/30분 주기) 수 사이클 동안 `invalid_access_token` 무재발.
4. 설정 페이지에 ESM+ 탭이 사라졌고 Cafe24 탭 상태 배지가 정상/재인증을 정확히 표시.

- [ ] **Step 4: (하네스) 결함 원장·결과서 갱신은 `sbshop-normalize` 검증 게이트에서 수행.**

---

## Self-Review 체크

- **Spec 커버리지:** DB 진실원화(Task 3)·advisory lock 조율(Task 1+3)·3종 저장(Task 3)·startup no-refresh(Task 3)·동시 refresh 단일화(Task 4)·ESM+ 섹션 제거(Task 5)·재인증 배지(Task 5, 기존 백엔드 메시지 활용)·범위 밖(SP-E/SP-F/Redis/DDL) 모두 대응됨. DDL 없음(컬럼 기존). ✅
- **Placeholder:** 없음 — 모든 코드/명령/기대출력 구체화. ✅
- **타입 일관성:** 생성자 `Cafe24TokenManager(MarketCredentialRepository, Cafe24OAuthTokenClient, TokenRefreshLock)`가 Task 3·4·기존 FailFast 테스트에서 일치. `TokenResponse(accessToken, refreshToken, expiresAt:Instant)`가 Task 2 정의와 Task 3/4 사용에서 일치. `runExclusively(long, Supplier<T>):T` 시그니처 일치. `TokenRefreshLock` 패키지 `core.domain.market`로 통일. ✅
- **주의(구현자):** Cafe24 응답에 `refresh_token`이 없을 수 있는 grant는 없음(refresh/authorization_code 모두 회전 토큰 반환) — 만약 특정 응답에서 refresh_token 누락 시 `Cafe24OAuthTokenHttpClient`에서 NPE 가능하므로 그 경우 기존 refresh_token 유지하도록 방어(구현 중 실응답으로 확인).
