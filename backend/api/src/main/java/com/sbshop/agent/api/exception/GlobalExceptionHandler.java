package com.sbshop.agent.api.exception;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		log.warn("경로변수/파라미터 타입 오류: {}={}", e.getName(), e.getValue());
		return ResponseEntity.badRequest().body(Map.of(
			"success", false,
			"message", "잘못된 값입니다: " + e.getName() + "=" + e.getValue()));
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException e) {
		log.warn("리소스 없음: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
			"success", false,
			"message", Objects.requireNonNullElse(e.getMessage(), "요청한 리소스를 찾을 수 없습니다.")));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
		log.warn("비즈니스 오류: {}", e.getMessage());
		return ResponseEntity.badRequest().body(Map.of(
			"success", false,
			"message", Objects.requireNonNullElse(e.getMessage(), "처리할 수 없는 요청 상태입니다.")));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
		log.warn("입력값 오류: {}", e.getMessage());
		return ResponseEntity.badRequest().body(Map.of(
			"success", false,
			"message", Objects.requireNonNullElse(e.getMessage(), "잘못된 요청입니다.")));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleGeneral(Exception e, HttpServletRequest request) {
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
