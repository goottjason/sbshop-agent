package com.sbshop.agent.api.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	@DisplayName("ResourceNotFoundException → 404 + {success:false, message}")
	void resourceNotFound_maps404() {
		ResponseEntity<Map<String, Object>> res = handler
			.handleNotFound(new ResourceNotFoundException("상품을 찾을 수 없습니다: 99"));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(res.getBody()).containsEntry("success", false);
		assertThat(res.getBody()).containsEntry("message", "상품을 찾을 수 없습니다: 99");
	}

	@Test
	@DisplayName("IllegalArgumentException은 여전히 400(입력오류와 404 구분)")
	void illegalArgument_still400() {
		ResponseEntity<Map<String, Object>> res = handler.handleIllegalArgument(new IllegalArgumentException("잘못된 값"));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("메시지 없는 ResourceNotFoundException도 404 + 기본 문구")
	void resourceNotFoundWithoutMessage_maps404WithDefault() {
		ResponseEntity<Map<String, Object>> res = handler.handleNotFound(new ResourceNotFoundException(null));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(res.getBody()).containsEntry("success", false);
		assertThat(res.getBody()).containsEntry("message", "요청한 리소스를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("메시지 없는 IllegalStateException도 400 + 기본 문구")
	void illegalStateWithoutMessage_maps400WithDefault() {
		ResponseEntity<Map<String, Object>> res = handler.handleIllegalState(new IllegalStateException());

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(res.getBody()).containsEntry("success", false);
		assertThat(res.getBody()).containsEntry("message", "처리할 수 없는 요청 상태입니다.");
	}

	@Test
	@DisplayName("메시지 없는 IllegalArgumentException도 400 + 기본 문구")
	void illegalArgumentWithoutMessage_maps400WithDefault() {
		ResponseEntity<Map<String, Object>> res = handler.handleIllegalArgument(new IllegalArgumentException());

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(res.getBody()).containsEntry("success", false);
		assertThat(res.getBody()).containsEntry("message", "잘못된 요청입니다.");
	}

	@Test
	@DisplayName("메시지 있는 IllegalStateException은 원래 메시지를 유지")
	void illegalStateWithMessage_keepsMessage() {
		ResponseEntity<Map<String, Object>> res = handler.handleIllegalState(new IllegalStateException("이미 발송된 주문입니다"));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(res.getBody()).containsEntry("message", "이미 발송된 주문입니다");
	}

	@Test
	@DisplayName("메시지 있는 IllegalArgumentException은 원래 메시지를 유지")
	void illegalArgumentWithMessage_keepsMessage() {
		ResponseEntity<Map<String, Object>> res = handler.handleIllegalArgument(new IllegalArgumentException("잘못된 값"));

		assertThat(res.getBody()).containsEntry("message", "잘못된 값");
	}
}
