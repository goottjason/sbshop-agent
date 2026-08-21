package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class Cafe24TokenManagerConcurrencyTest {

	static class BarrierSerializingLock implements TokenRefreshLock {
		private final CyclicBarrier barrier;
		private final ReentrantLock lock = new ReentrantLock();

		BarrierSerializingLock(int parties) {
			this.barrier = new CyclicBarrier(parties);
		}

		@Override
		public <T> T runExclusively(long key, Supplier<T> action) {
			try {
				barrier.await(10, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new RuntimeException("barrier await failed (a thread never reached the lock)", e);
			}
			lock.lock();
			try {
				return action.get();
			} finally {
				lock.unlock();
			}
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
		c.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));

		MarketCredentialRepository repo = Mockito.mock(MarketCredentialRepository.class);
		Mockito.when(repo.findByMarketType(MarketType.CAFE24)).thenReturn(Optional.of(c));

		AtomicInteger exchangeCalls = new AtomicInteger();
		Cafe24OAuthTokenClient client = (mallId, id, secret, payload) -> {
			exchangeCalls.incrementAndGet();
			return new Cafe24OAuthTokenClient.TokenResponse(
				"AT-NEW", "RT2", Instant.now().plusSeconds(7200));
		};

		int threads = 12;
		var manager = new Cafe24TokenManager(repo, client, new BarrierSerializingLock(threads));

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		var results = new ConcurrentLinkedQueue<String>();
		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				ready.countDown();
				try {
					go.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				results.add(manager.getValidAccessToken());
			});
		}
		ready.await();
		go.countDown();
		pool.shutdown();
		boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS);
		assertThat(finished).as("all threads finished within timeout").isTrue();

		assertThat(exchangeCalls.get()).isEqualTo(1);
		assertThat(results).allMatch("AT-NEW"::equals);
		assertThat(results).hasSize(threads);
	}
}
