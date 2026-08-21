package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24TokenManagerFailFastTest {

	@Mock
	private MarketCredentialRepository marketCredentialRepository;
	@Mock
	private Cafe24OAuthTokenClient tokenClient;

	@Test
	@DisplayName("credential/refresh token이 없어 토큰을 못 얻으면 IllegalStateException으로 즉시 실패한다")
	void throwsWhenAccessTokenUnavailable() {
		when(marketCredentialRepository.findByMarketType(org.mockito.ArgumentMatchers.any()))
			.thenReturn(Optional.empty());

		Cafe24TokenManager manager = new Cafe24TokenManager(
			marketCredentialRepository, tokenClient,
			new TokenRefreshLock() {
				@Override public <T> T runExclusively(long key, Supplier<T> a) {
					return a.get();
				}
			});

		assertThatThrownBy(manager::getValidAccessToken)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("재인증");
	}
}
