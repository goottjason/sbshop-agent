package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-075(CF24-2): 토큰 갱신에 실패해(accessToken=null) refresh가 되지 않는 상황에서
 * {@code getValidAccessToken()}이 조용히 null을 반환하면 상위에서 "Bearer null"로 API를 호출해
 * 401이 나고 실패 원인이 "Cafe24 API 호출 실패"로 은폐된다. 그래서 토큰이 없으면
 * 그 자리에서 재인증 필요를 알리는 예외로 즉시 실패(fail-fast)해야 한다는 계약을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24TokenManagerFailFastTest {

	@Mock private MarketCredentialRepository marketCredentialRepository;
	@Mock private com.sbshop.agent.infrastructure.client.cafe24.Cafe24OAuthTokenClient tokenClient;

	@Test
	@DisplayName("credential/refresh token이 없어 토큰을 못 얻으면 IllegalStateException으로 즉시 실패한다")
	void throwsWhenAccessTokenUnavailable() {
		// credential 자체가 없어 refreshAccessToken()이 조기 반환 → accessToken은 null로 유지된다.
		when(marketCredentialRepository.findByMarketType(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.empty());

		Cafe24TokenManager manager = new Cafe24TokenManager(
			marketCredentialRepository, tokenClient,
			new com.sbshop.agent.core.domain.market.TokenRefreshLock() {
				@Override public <T> T runExclusively(long key, Supplier<T> a) {
					return a.get();
				}
			});

		assertThatThrownBy(manager::getValidAccessToken)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("재인증");
	}
}
