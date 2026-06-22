package com.sbshop.agent.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
		log.warn("비즈니스 오류: {}", e.getMessage());
		return ResponseEntity.badRequest().body(Map.of(
			"success", false,
			"message", e.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
		log.warn("입력값 오류: {}", e.getMessage());
		return ResponseEntity.badRequest().body(Map.of(
			"success", false,
			"message", e.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleGeneral(Exception e, HttpServletRequest request) {
		// SSE 스트림 요청 중 오류는 JSON 변환 불가 → 로그만 남기고 빈 응답 반환
		if (MediaType.TEXT_EVENT_STREAM_VALUE.equals(request.getHeader("Accept"))) {
			log.error("SSE 스트림 오류", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
		log.error("예상치 못한 오류", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
			"success", false,
			"message", "서버 내부 오류가 발생했습니다: " + e.getMessage()));
	}
}
