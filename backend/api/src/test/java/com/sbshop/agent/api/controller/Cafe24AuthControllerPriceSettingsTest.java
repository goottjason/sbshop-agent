package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24AuthControllerPriceSettingsTest {

	@Mock
	Cafe24TokenManager tokenManager;
	@Mock
	Cafe24RestClient restClient;
	@Mock
	Cafe24OrderApiPort orders;
	@Mock
	ActionLogService actionLog;

	private Cafe24AuthController controller() {
		return new Cafe24AuthController(tokenManager, restClient, orders, actionLog);
	}

	@Test
	void authorizationUsesSavedCredentialUrl() {
		when(tokenManager.authorizationUrlForSavedCredential()).thenReturn("https://saved.example/authorize");
		assertThat(controller().authorizationUrl()).containsEntry("url", "https://saved.example/authorize");
		verifyNoInteractions(restClient, orders, actionLog);
	}

	@Test
	void confirmsPermissionWithActualShopSettingsResponse() {
		when(restClient.get("/admin/products/setting?shop_no=1"))
			.thenReturn("{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"C\"}}");
		assertThat(controller().priceSettingsStatus().state()).isEqualTo("READABLE");
		verify(restClient).get("/admin/products/setting?shop_no=1");
		verifyNoInteractions(tokenManager, orders, actionLog);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "null", "", "<html>bad gateway</html>",
		"{\"product\":[]}", "{\"product\":{}}", "{\"product\":{\"shop_no\":2}}",
		"{\"product\":{\"shop_no\":1.5}}", "{\"error\":{},\"product\":{\"shop_no\":1}}"})
	void incompleteOrUnrelatedResponsesCannotBeGreen(String body) {
		when(restClient.get("/admin/products/setting?shop_no=1")).thenReturn(body);
		assertThat(controller().priceSettingsStatus().state()).isEqualTo("UNAVAILABLE");
	}

	@ParameterizedTest
	@CsvSource({"403 insufficient_scope,MISSING_SCOPE", "401 invalid_token,AUTH_REQUIRED",
		"invalid_grant,AUTH_REQUIRED", "재인증이 필요합니다,AUTH_REQUIRED",
		"403 Forbidden,UNAVAILABLE", "429 Too Many Requests,UNAVAILABLE",
		"503 Unavailable,UNAVAILABLE", "Connection refused,UNAVAILABLE", "Request 14012 timed out,UNAVAILABLE"})
	void classifiesNestedFailuresWithoutExposingRawResponse(String detail, String expected) {
		when(restClient.get("/admin/products/setting?shop_no=1"))
			.thenThrow(new IllegalStateException("request failed", new RuntimeException(detail + " PRIVATE_MARKER")));
		var result = controller().priceSettingsStatus();
		assertThat(result.state()).isEqualTo(expected);
		assertThat(result.message()).doesNotContain("PRIVATE_MARKER");
	}

	@Test
	void errorInsideSuccessfulHttpResponseIsStillMissingScope() {
		when(restClient.get("/admin/products/setting?shop_no=1"))
			.thenReturn("{\"error\":{\"code\":403,\"message\":\"insufficient_scope\"}}");
		assertThat(controller().priceSettingsStatus().state()).isEqualTo("MISSING_SCOPE");
	}
}
