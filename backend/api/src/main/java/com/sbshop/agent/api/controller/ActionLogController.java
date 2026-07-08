package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.actionlog.ActionLogResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

/**
 * 사용자 액션 로그 조회 API (D-042)
 */
@RestController
@RequestMapping("/api/v1/action-logs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ActionLogController {

	private final ActionLogService actionLogService;

	/** 최근 액션 로그를 시간 역순으로 반환 */
	@GetMapping
	public ResponseEntity<List<ActionLogResponse>> getActionLogs(
		@RequestParam(name = "limit", defaultValue = "100") int limit) {
		List<ActionLogResponse> logs = actionLogService.recentLogs(limit).stream()
			.map(ActionLogResponse::from)
			.toList();
		return ResponseEntity.ok(logs);
	}
}
