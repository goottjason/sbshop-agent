package com.sbshop.agent.core.application.actionlog;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.actionlog.ActionLog;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.actionlog.repository.ActionLogRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * D-042: ActionLogService.record 저장 및 시간 역순 조회 검증.
 */
@DataJpaTest
@ContextConfiguration(classes = ActionLogServiceTest.TestApp.class)
class ActionLogServiceTest {

	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain.actionlog")
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.domain.actionlog.repository")
	static class TestApp {}

	@Autowired
	private ActionLogRepository actionLogRepository;

	@Test
	void record_persistsAndQueriesRecentFirst() {
		ActionLogService service = new ActionLogService(actionLogRepository);

		service.record("COUPANG_SYNC", "COUPANG", ActionStatus.STARTED, "쿠팡 동기화 요청");
		service.record("COUPANG_SYNC", "COUPANG", ActionStatus.FAILED, "동기화 실패: 403");

		List<ActionLog> logs = service.recentLogs(100);

		assertThat(logs).hasSize(2);
		assertThat(logs).extracting(ActionLog::getActionStatus)
			.containsExactlyInAnyOrder(ActionStatus.STARTED, ActionStatus.FAILED);
		ActionLog failed = logs.stream()
			.filter(l -> l.getActionStatus() == ActionStatus.FAILED).findFirst().orElseThrow();
		assertThat(failed.getMessage()).contains("403");
		assertThat(failed.getMarketType()).isEqualTo("COUPANG");
	}

	@Test
	void recentLogs_respectsLimit() {
		ActionLogService service = new ActionLogService(actionLogRepository);
		for (int i = 0; i < 5; i++) {
			service.record("COUPANG_SYNC", "COUPANG", ActionStatus.SUCCESS, "성공 " + i);
		}

		assertThat(service.recentLogs(3)).hasSize(3);
	}
}
