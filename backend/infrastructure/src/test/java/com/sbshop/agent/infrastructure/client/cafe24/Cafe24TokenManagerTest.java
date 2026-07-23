package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.Instant;
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

	@Test
	@DisplayName("만료 토큰이면 refresh 1회 호출 후 access/refresh/expiry 3종을 저장한다")
	void refreshesAndPersistsAllThree() {
		MarketCredential c = credential("AT-OLD",
			LocalDateTime.now().minusMinutes(1), "RT1"); // 만료
		when(repo.findByMarketType(any())).thenReturn(Optional.of(c));
		java.time.Instant expectedExpiry = java.time.Instant.now().plusSeconds(7200);
		when(tokenClient.exchange(any(), any(), any(), any()))
			.thenReturn(new Cafe24OAuthTokenClient.TokenResponse(
				"AT-NEW", "RT2", expectedExpiry));

		var manager = new Cafe24TokenManager(repo, tokenClient, DIRECT_LOCK);
		String token = manager.getValidAccessToken();

		assertThat(token).isEqualTo("AT-NEW");
		assertThat(c.getAccessToken()).isEqualTo("AT-NEW");
		assertThat(c.getRefreshToken()).isEqualTo("RT2");
		assertThat(c.getTokenExpiresAt())
			.isEqualTo(java.time.LocalDateTime.ofInstant(expectedExpiry, java.time.ZoneId.of("Asia/Seoul")));
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

	@Test
	@DisplayName("exchange가 refreshToken=null 반환 시 기존 refresh_token을 보존한다")
	void preservesExistingRefreshTokenWhenResponseOmitsIt() {
		MarketCredential c = credential("AT-OLD",
			LocalDateTime.now().minusMinutes(1), "RT1"); // 만료, 기존 RT1
		when(repo.findByMarketType(any())).thenReturn(Optional.of(c));
		// Cafe24가 refresh_token을 생략(null)해서 반환
		when(tokenClient.exchange(any(), any(), any(), any()))
			.thenReturn(new Cafe24OAuthTokenClient.TokenResponse(
				"AT-NEW", null, Instant.now().plusSeconds(7200)));

		var manager = new Cafe24TokenManager(repo, tokenClient, DIRECT_LOCK);
		String token = manager.getValidAccessToken();

		assertThat(token).isEqualTo("AT-NEW");
		assertThat(c.getAccessToken()).isEqualTo("AT-NEW");
		// 기존 RT1이 유지되어야 한다 — null로 덮어쓰면 안 됨
		assertThat(c.getRefreshToken()).isEqualTo("RT1");
		assertThat(c.getTokenExpiresAt()).isNotNull();
		verify(repo).save(c);
	}

	// ─── D-103: 선제 갱신(트래픽 독립) — 리프레시 토큰 2주 시한 만료 방지 ───

	@Test
	@DisplayName("선제 갱신: refresh token이 있으면 access token 유효 여부와 무관하게 refresh를 강제해 회전시킨다")
	void proactiveRefreshForcesRotationEvenWhenAccessValid() {
		MarketCredential c = credential("AT-VALID",
			LocalDateTime.now().plusHours(1), "RT1"); // access token은 아직 유효
		when(repo.findByMarketType(any())).thenReturn(Optional.of(c));
		when(tokenClient.exchange(any(), any(), any(), any()))
			.thenReturn(new Cafe24OAuthTokenClient.TokenResponse(
				"AT-NEW", "RT2", Instant.now().plusSeconds(7200)));

		var manager = new Cafe24TokenManager(repo, tokenClient, DIRECT_LOCK);
		manager.refreshProactively();

		// access token이 유효해도 강제 refresh → 리프레시 토큰 회전(시한 연장)
		verify(tokenClient).exchange(any(), any(), any(), any());
		assertThat(c.getRefreshToken()).isEqualTo("RT2");
		assertThat(c.getAccessToken()).isEqualTo("AT-NEW");
		verify(repo).save(c);
	}

	@Test
	@DisplayName("선제 갱신: refresh token이 없으면 exchange 없이 조용히 건너뛴다(예외 없음)")
	void proactiveRefreshSkipsWhenNoRefreshToken() {
		MarketCredential c = credential("AT",
			LocalDateTime.now().plusHours(1), null); // refresh token 없음
		when(repo.findByMarketType(any())).thenReturn(Optional.of(c));

		var manager = new Cafe24TokenManager(repo, tokenClient, DIRECT_LOCK);
		manager.refreshProactively(); // 예외 없이 리턴해야 한다

		verify(tokenClient, never()).exchange(any(), any(), any(), any());
	}

	@Test
	@DisplayName("선제 갱신: refresh 실패 시 예외를 삼켜 스케줄러가 죽지 않게 한다")
	void proactiveRefreshSwallowsFailure() {
		MarketCredential c = credential("AT",
			LocalDateTime.now().plusHours(1), "RT1");
		when(repo.findByMarketType(any())).thenReturn(Optional.of(c));
		when(tokenClient.exchange(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("boom"));

		var manager = new Cafe24TokenManager(repo, tokenClient, DIRECT_LOCK);
		// 예외가 전파되지 않아야 한다(스케줄러 보호)
		manager.refreshProactively();

		verify(tokenClient).exchange(any(), any(), any(), any());
	}
}
