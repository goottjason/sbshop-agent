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
