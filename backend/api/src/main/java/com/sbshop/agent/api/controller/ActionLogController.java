package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.actionlog.ActionLogResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/action-logs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ActionLogController {

	private final ActionLogService actionLogService;

	static final int DEFAULT_LIMIT = 100;
	static final int MAX_LIMIT = 500;

	@GetMapping
	public ResponseEntity<List<ActionLogResponse>> getActionLogs(
		@RequestParam(name = "limit", defaultValue = "100")
		int limit,
		@RequestParam(name = "page", defaultValue = "0")
		int page) {
		int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
		int safePage = Math.max(page, 0);
		List<ActionLogResponse> logs = actionLogService.recentLogs(safePage, safeLimit).stream()
			.map(ActionLogResponse::from)
			.toList();
		return ResponseEntity.ok(logs);
	}
}
