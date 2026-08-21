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
