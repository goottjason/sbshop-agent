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

	/**
	 * F-MISC-1: page 파라미터로 오프셋 기반 페이지네이션 지원(윈도우 개수 계약).
	 * 5건 존재 시 size=2 페이지들은 각각 2,2,1건을 반환하고, 오프셋을 벗어난 페이지는 0건이다.
	 * (createdAt 동률 시 tie-break 순서는 비결정적이므로 개수 계약만 결정적으로 검증한다.)
	 */
	@Test
	void recentLogs_withPage_returnsOffsetWindow() {
		ActionLogService service = new ActionLogService(actionLogRepository);
		for (int i = 0; i < 5; i++) {
			service.record("COUPANG_SYNC", "COUPANG", ActionStatus.SUCCESS, "성공 " + i);
		}

		assertThat(service.recentLogs(0, 2)).hasSize(2); // 0~1
		assertThat(service.recentLogs(1, 2)).hasSize(2); // 2~3
		assertThat(service.recentLogs(2, 2)).hasSize(1); // 4 (마지막 부분 페이지)
		assertThat(service.recentLogs(3, 2)).isEmpty();  // 오프셋 초과 → 빈 페이지
	}

	/**
	 * F-MISC-1: 기존 recentLogs(limit) 계약은 page=0 offset을 유지해야 한다(비파괴).
	 */
	@Test
	void recentLogs_singleArg_isFirstPage() {
		ActionLogService service = new ActionLogService(actionLogRepository);
		for (int i = 0; i < 5; i++) {
			service.record("COUPANG_SYNC", "COUPANG", ActionStatus.SUCCESS, "성공 " + i);
		}

		assertThat(service.recentLogs(100)).hasSize(5);
		assertThat(service.recentLogs(0, 100)).hasSize(5);
	}

	/**
	 * F-MISC-1/F-MISC-2: 음수 page는 0으로, 비정상 size는 안전 상한으로 방어한다.
	 */
	@Test
	void recentLogs_defendsInvalidPageAndSize() {
		ActionLogService service = new ActionLogService(actionLogRepository);
		service.record("COUPANG_SYNC", "COUPANG", ActionStatus.SUCCESS, "성공");

		// 음수 page → 첫 페이지로 보정, 예외 없이 동작
		assertThat(service.recentLogs(-1, 10)).hasSize(1);
		// size<=0 → 기본 100, 상한 500
		assertThat(service.recentLogs(0, 0)).hasSize(1);
		assertThat(service.recentLogs(0, 999_999)).hasSize(1);
	}
}
