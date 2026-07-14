package com.sbshop.agent.api.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sbshop.agent.api.controller.MarketCredentialController;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.market.MarketCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SP-7 / F-CRED-4: enum 타입 경로변수 바인딩 실패가 500이 아니라 400으로 표면화되는지 검증.
 *
 * <p>{@code GET /api/v1/market-credentials/{marketType}}에서 {@code marketType}이
 * {@code MarketType} enum으로 바인딩되지 못하면 Spring MVC가
 * {@code MethodArgumentTypeMismatchException}을 던진다. 이 예외는 사용자 입력 오류이므로
 * 400이어야 하지만, 전용 핸들러가 없으면 {@code GlobalExceptionHandler}의 일반
 * {@code Exception} 핸들러로 떨어져 500으로 응답된다(Red).
 */
@ExtendWith(MockitoExtension.class)
class EnumPathVariableMismatchTest {

	@Mock
	private MarketCredentialService marketCredentialService;
	@Mock
	private ActionLogService actionLogService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new MarketCredentialController(marketCredentialService, actionLogService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("잘못된 marketType enum 경로변수 → 400 + success:false 바디")
	void invalidEnumPathVariable_returns400() throws Exception {
		mockMvc.perform(get("/api/v1/market-credentials/FOO"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").exists());
	}
}
