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

	/** 컨트롤러 계약 상한/기본값 (서비스 방어와 일관). F-MISC-2 */
	static final int DEFAULT_LIMIT = 100;
	static final int MAX_LIMIT = 500;

	/**
	 * 최근 액션 로그를 시간 역순으로 반환한다.
	 *
	 * <p>응답은 평면 배열(JSON array)로 유지된다 — 프론트({@code actionLogApi.getActionLogs})가
	 * {@code res.data}를 배열로 소비하므로 Page 엔벌로프로 바꾸지 않는다.
	 *
	 * <p>F-MISC-1: {@code page}(0-base) 옵셔널 파라미터로 오프셋 페이지네이션을 지원한다.
	 * 프론트는 이 파라미터를 보내지 않으므로 기본 {@code page=0}은 기존 동작과 동일하다(비파괴).
	 *
	 * <p>F-MISC-2: limit 상하한·page 하한 방어를 컨트롤러 계약에서 명시적으로 수행하고,
	 * 정규화된 값을 서비스로 전달한다(서비스도 동일 방어를 유지 — 다중 방어).
	 */
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
