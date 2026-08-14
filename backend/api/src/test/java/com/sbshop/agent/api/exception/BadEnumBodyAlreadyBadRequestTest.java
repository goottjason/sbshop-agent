package com.sbshop.agent.api.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sbshop.agent.api.controller.ProductSourcingController;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.ProductPublishUseCase;
import com.sbshop.agent.core.application.sourcing.ProductSourcingUseCase;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SP-7 확정 근거: 본문에서 {@code MarketType.valueOf(...)}를 호출하는 경로변수(String 파라미터)는
 * {@code IllegalArgumentException}을 던지므로, {@code GlobalExceptionHandler}의 기존
 * {@code IllegalArgumentException} 핸들러에 의해 이미 400으로 응답된다(F-PSRC-12는 실제로는 400).
 *
 * <p>이 테스트는 "잘못된 marketType → 500"이라는 원장 주장 중 일부가 실제로는 이미 400임을 고정한다.
 * (enum을 직접 경로변수 타입으로 받는 F-CRED-4만이 실제 500이었다 — {@link EnumPathVariableMismatchTest}.)
 */
@ExtendWith(MockitoExtension.class)
class BadEnumBodyAlreadyBadRequestTest {

	@Mock
	private ProductSourcingUseCase productSourcingUseCase;
	@Mock
	private ProductCreateUseCase productCreateUseCase;
	@Mock
	private ProductPublishUseCase productPublishUseCase;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private ActionLogService actionLogService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new ProductSourcingController(
				productSourcingUseCase, productCreateUseCase, productPublishUseCase,
				marketRegistrationRepository, actionLogService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("본문 valueOf 경유 잘못된 marketType → 이미 400 (500 아님)")
	void invalidMarketTypeInBody_isAlready400() throws Exception {
		mockMvc.perform(post("/api/v1/products/1/markets/FOO"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false));
	}
}
