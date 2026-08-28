package com.sbshop.agent.core.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.sync.MarketSyncStatus;
import com.sbshop.agent.core.domain.sync.repository.MarketSyncStatusRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = SyncStatusCountsTest.TestApp.class)
class SyncStatusCountsTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain.sync")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.sync.repository")
	static class TestApp {}

	@Autowired
	private MarketSyncStatusRepository repository;

	@Test
	@DisplayName("완료 시 처리·신규 건수가 저장돼 0건 성공이 사후에 구분된다")
	void markCompleted_persistsCounts() {
		SyncStatusService service = new SyncStatusService(repository);

		service.markRunning(SyncMarketKeys.GMARKET);
		service.markCompleted(SyncMarketKeys.GMARKET, 12, 3);

		MarketSyncStatus saved = repository.findByMarketType(SyncMarketKeys.GMARKET).orElseThrow();
		assertThat(saved.getProcessedCount()).isEqualTo(12);
		assertThat(saved.getNewCount()).isEqualTo(3);
		assertThat(saved.getLastNewAt()).isNotNull();
	}

	@Test
	@DisplayName("신규 0건 회차는 마지막 신규 유입 시각을 갱신하지 않는다 — 공백 측정의 기준선")
	void markCompleted_zeroNew_keepsLastNewAt() {
		SyncStatusService service = new SyncStatusService(repository);

		service.markCompleted(SyncMarketKeys.GMARKET, 5, 2);
		LocalDateTime firstNewAt = repository.findByMarketType(SyncMarketKeys.GMARKET).orElseThrow().getLastNewAt();

		service.markCompleted(SyncMarketKeys.GMARKET, 5, 0);

		MarketSyncStatus saved = repository.findByMarketType(SyncMarketKeys.GMARKET).orElseThrow();
		assertThat(saved.getLastNewAt()).isEqualTo(firstNewAt);
		assertThat(saved.getNewCount()).isZero();
		assertThat(saved.getProcessedCount()).isEqualTo(5);
	}

	@Test
	@DisplayName("lastNewAt은 신규 유입이 한 번도 없으면 비어 있다")
	void lastNewAt_emptyWhenNeverNew() {
		SyncStatusService service = new SyncStatusService(repository);

		service.markCompleted(SyncMarketKeys.COUPANG, 4, 0);

		assertThat(service.lastNewAt(SyncMarketKeys.COUPANG)).isEmpty();
	}

	@Test
	@DisplayName("건수 없는 markCompleted는 기존 건수를 지우지 않는다")
	void markCompleted_withoutCounts_keepsExistingCounts() {
		SyncStatusService service = new SyncStatusService(repository);

		service.markCompleted(SyncMarketKeys.COUPANG, 7, 2);
		service.markCompleted(SyncMarketKeys.COUPANG);

		MarketSyncStatus saved = repository.findByMarketType(SyncMarketKeys.COUPANG).orElseThrow();
		assertThat(saved.getProcessedCount()).isEqualTo(7);
		assertThat(saved.getNewCount()).isEqualTo(2);
		assertThat(saved.getLastNewAt()).isNotNull();
	}
}
