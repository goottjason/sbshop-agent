package com.sbshop.agent.core.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.sync.repository.MarketSyncStatusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = SyncStatusTryMarkRunningTest.TestApp.class)
class SyncStatusTryMarkRunningTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain.sync")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.sync.repository")
	static class TestApp {}

	@Autowired
	private MarketSyncStatusRepository repository;

	@Test
	void tryMarkRunning_claimsWhenNoRowExists() {
		SyncStatusService service = new SyncStatusService(repository);

		boolean claimed = service.tryMarkRunning("COUPANG_SETTLEMENT");

		assertThat(claimed).isTrue();
		assertThat(repository.findByMarketType("COUPANG_SETTLEMENT")).isPresent();
		assertThat(repository.findByMarketType("COUPANG_SETTLEMENT").get().getSyncStatus())
			.isEqualTo("RUNNING");
	}

	@Test
	void tryMarkRunning_skipsWhenAlreadyRunning() {
		SyncStatusService service = new SyncStatusService(repository);

		boolean first = service.tryMarkRunning("COUPANG_SETTLEMENT");
		boolean second = service.tryMarkRunning("COUPANG_SETTLEMENT");

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void tryMarkRunning_reclaimsAfterRelease() {
		SyncStatusService service = new SyncStatusService(repository);

		assertThat(service.tryMarkRunning("COUPANG_SETTLEMENT")).isTrue();
		service.markCompleted("COUPANG_SETTLEMENT");
		assertThat(service.tryMarkRunning("COUPANG_SETTLEMENT")).isTrue();

		assertThat(repository.count()).isEqualTo(1);
		assertThat(repository.findByMarketType("COUPANG_SETTLEMENT").get().getSyncStatus())
			.isEqualTo("RUNNING");
	}

	@Test
	void tryMarkRunning_reclaimsAfterFailure() {
		SyncStatusService service = new SyncStatusService(repository);

		assertThat(service.tryMarkRunning("COUPANG_SETTLEMENT")).isTrue();
		service.markFailed("COUPANG_SETTLEMENT", "boom");
		assertThat(service.tryMarkRunning("COUPANG_SETTLEMENT")).isTrue();

		assertThat(repository.count()).isEqualTo(1);
	}
}
