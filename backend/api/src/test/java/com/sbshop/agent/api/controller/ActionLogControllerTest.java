package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.actionlog.ActionLogResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * F-MISC-1/F-MISC-2: action-logs 조회 컨트롤러 계약 검증.
 * - 응답은 프론트 계약 유지를 위해 평면 배열(List)이어야 한다.
 * - limit/page 기본값·방어가 컨트롤러 계약에서 명시적으로 서비스에 전달돼야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ActionLogControllerTest {

	@Mock
	ActionLogService actionLogService;

	private ActionLogController controller() {
		return new ActionLogController(actionLogService);
	}

	@Test
	@DisplayName("응답은 평면 배열(List<ActionLogResponse>)로 유지된다 (프론트 계약)")
	void returnsFlatArray() {
		when(actionLogService.recentLogs(anyInt(), anyInt())).thenReturn(List.of());

		ResponseEntity<List<ActionLogResponse>> res =
			controller().getActionLogs(100, 0);

		assertThat(res.getBody()).isNotNull();
		assertThat(res.getBody()).isInstanceOf(List.class);
	}

	@Test
	@DisplayName("page 파라미터가 서비스로 전달된다 (오프셋 페이지네이션)")
	void passesPageToService() {
		when(actionLogService.recentLogs(anyInt(), anyInt())).thenReturn(List.of());

		controller().getActionLogs(50, 2);

		verify(actionLogService).recentLogs(2, 50);
	}

	@Test
	@DisplayName("음수 page는 컨트롤러에서 0으로 방어된다")
	void clampsNegativePage() {
		when(actionLogService.recentLogs(anyInt(), anyInt())).thenReturn(List.of());

		controller().getActionLogs(100, -5);

		verify(actionLogService).recentLogs(0, 100);
	}

	@Test
	@DisplayName("limit 상하한이 컨트롤러 계약에서 방어된다")
	void clampsLimitBounds() {
		when(actionLogService.recentLogs(anyInt(), anyInt())).thenReturn(List.of());

		controller().getActionLogs(0, 0);
		verify(actionLogService).recentLogs(0, 100); // limit<=0 → 기본 100

		controller().getActionLogs(9999, 0);
		verify(actionLogService).recentLogs(0, 500); // 상한 500
	}
}
