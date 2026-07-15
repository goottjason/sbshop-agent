package com.sbshop.agent.core.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.sync.SyncStatusService.SyncStatus;
import com.sbshop.agent.core.domain.sync.repository.MarketSyncStatusRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * SP-2 (F-SYNC-1): SyncStatusService가 인메모리(ConcurrentHashMap)가 아니라 DB에 상태를 영속화하는지 검증.
 * api·worker가 서로 다른 JVM이라 인메모리 상태는 공유되지 않으므로 DB 단일 원본이어야 한다.
 */
@DataJpaTest
@ContextConfiguration(classes = SyncStatusServiceTest.TestApp.class)
class SyncStatusServiceTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain.sync")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.sync.repository")
	static class TestApp {}

	@Autowired
	private MarketSyncStatusRepository repository;

	@Test
	void markRunning_persistsRunningStatus() {
		SyncStatusService service = new SyncStatusService(repository);

		service.markRunning("COUPANG");

		Map<String, SyncStatus> statuses = service.getAllStatuses();
		assertThat(statuses).containsKey("COUPANG");
		assertThat(statuses.get("COUPANG").getStatus()).isEqualTo("RUNNING");
		// DB에 실제 저장됐는지 확인 — 다른 서비스 인스턴스가 읽어도 보여야 함
		assertThat(repository.findByMarketType("COUPANG")).isPresent();
	}

	@Test
	void markCompleted_persistsCompletedStatusWithTimestamp() {
		SyncStatusService service = new SyncStatusService(repository);

		service.markRunning("SMART_STORE");
		service.markCompleted("SMART_STORE");

		SyncStatus status = service.getAllStatuses().get("SMART_STORE");
		assertThat(status.getStatus()).isEqualTo("COMPLETED");
		assertThat(status.getLastSyncAt()).isNotNull();
	}

	@Test
	void markFailed_persistsFailedStatusWithErrorMessage() {
		SyncStatusService service = new SyncStatusService(repository);

		service.markRunning("ELEVEN_STREET");
		service.markFailed("ELEVEN_STREET", "403 Forbidden");

		SyncStatus status = service.getAllStatuses().get("ELEVEN_STREET");
		assertThat(status.getStatus()).isEqualTo("FAILED");
		assertThat(status.getErrorMessage()).isEqualTo("403 Forbidden");
	}

	@Test
	void repeatedMarks_upsertSingleRowPerMarket() {
		SyncStatusService service = new SyncStatusService(repository);

		service.markRunning("COUPANG");
		service.markCompleted("COUPANG");
		service.markRunning("COUPANG");

		// 마켓당 단일 row 유지(중복 insert 금지)
		assertThat(repository.count()).isEqualTo(1);
		assertThat(service.getAllStatuses().get("COUPANG").getStatus()).isEqualTo("RUNNING");
	}
}
