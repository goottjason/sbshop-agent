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
		ResponseEntity<Map<String, Object>> res =
			handler.handleNotFound(new ResourceNotFoundException("상품을 찾을 수 없습니다: 99"));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(res.getBody()).containsEntry("success", false);
		assertThat(res.getBody()).containsEntry("message", "상품을 찾을 수 없습니다: 99");
	}

	@Test
	@DisplayName("IllegalArgumentException은 여전히 400(입력오류와 404 구분)")
	void illegalArgument_still400() {
		ResponseEntity<Map<String, Object>> res =
			handler.handleIllegalArgument(new IllegalArgumentException("잘못된 값"));

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}
}
