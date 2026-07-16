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

/**
 * R3 / F-SYNC-17: 정산 동기화 중복 실행 가드.
 *
 * <p>정산(syncCoupangSettlement)은 워커 스케줄러 + api 수동으로 교차 JVM 트리거 가능하다.
 * in-JVM AtomicBoolean으로는 부족하므로 DB 상태(SyncStatus) 원자 클레임으로 중복을 막는다.
 * {@code tryMarkRunning}은 이미 RUNNING이면 false(스킵), 아니면 RUNNING 세팅 후 true를 원자적으로 반환한다.
 */
@DataJpaTest
@ContextConfiguration(classes = SyncStatusTryMarkRunningTest.TestApp.class)
class SyncStatusTryMarkRunningTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain.sync")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.sync.repository")
	static class TestApp {}

	@Autowired
	private MarketSyncStatusRepository repository;

	/** 최초 클레임: row가 없을 때 RUNNING 세팅 후 true 반환, 단일 row 생성. */
	@Test
	void tryMarkRunning_claimsWhenNoRowExists() {
		SyncStatusService service = new SyncStatusService(repository);

		boolean claimed = service.tryMarkRunning("COUPANG_SETTLEMENT");

		assertThat(claimed).isTrue();
		assertThat(repository.findByMarketType("COUPANG_SETTLEMENT")).isPresent();
		assertThat(repository.findByMarketType("COUPANG_SETTLEMENT").get().getSyncStatus())
			.isEqualTo("RUNNING");
	}

	/** 이미 RUNNING이면 두 번째 클레임은 false(스킵), row는 그대로 RUNNING 단일. */
	@Test
	void tryMarkRunning_skipsWhenAlreadyRunning() {
		SyncStatusService service = new SyncStatusService(repository);

		boolean first = service.tryMarkRunning("COUPANG_SETTLEMENT");
		boolean second = service.tryMarkRunning("COUPANG_SETTLEMENT");

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		assertThat(repository.count()).isEqualTo(1);
	}

	/** 완료로 해제하면 재클레임 성공(정상적인 다음 사이클 진입). */
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

	/** FAILED로 해제된 뒤에도 재클레임 성공. */
	@Test
	void tryMarkRunning_reclaimsAfterFailure() {
		SyncStatusService service = new SyncStatusService(repository);

		assertThat(service.tryMarkRunning("COUPANG_SETTLEMENT")).isTrue();
		service.markFailed("COUPANG_SETTLEMENT", "boom");
		assertThat(service.tryMarkRunning("COUPANG_SETTLEMENT")).isTrue();

		assertThat(repository.count()).isEqualTo(1);
	}
}
