package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * SP-11 / F-CAFE-12: {@code /issue-token}과 {@code /auth/callback}이 공유하는
 * 인가코드→토큰 교환 로직을 특성화(characterization)한다.
 *
 * <p>중복 제거 리팩토링(동작 불변) 전후로 이 테스트가 동일하게 통과해야 한다.
 * 두 엔드포인트가 code 추출 + {@link Cafe24TokenManager#issueInitialToken} 호출을
 * 공유하되, <b>비대칭(활동로그 유무·응답 형태)</b>이 보존됨을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24AuthControllerTokenExchangeTest {

	@Mock
	private Cafe24TokenManager cafe24TokenManager;
	@Mock
	private Cafe24RestClient cafe24RestClient;
	@Mock
	private Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock
	private ActionLogService actionLogService;

	private Cafe24AuthController controller() {
		return new Cafe24AuthController(cafe24TokenManager, cafe24RestClient,
			cafe24OrderApiPort, actionLogService);
	}

	// ── 공유 로직: 전체 리다이렉트 URL을 붙여넣어도 code만 추출해 issueInitialToken에 전달 ──

	@Test
	@DisplayName("issue-token: 전체 URL을 붙여넣어도 code만 추출해 토큰 발급한다")
	void issueToken_extractsCodeFromFullUrl() {
		var response = controller().issueToken(
			new Cafe24AuthController.IssueTokenRequest("https://x?code=ABC123&foo=1"));

		verify(cafe24TokenManager).issueInitialToken("ABC123");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().connected()).isTrue();
	}

	@Test
	@DisplayName("auth/callback: 전체 URL을 붙여넣어도 code만 추출해 토큰 발급한다")
	void callback_extractsCodeFromFullUrl() {
		var response = controller().handleCafe24AuthCode("https://x?code=ABC123&foo=1");

		verify(cafe24TokenManager).issueInitialToken("ABC123");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Cafe24 인증이 완료");
	}

	// ── 비대칭 1: issue-token만 활동로그를 남긴다 (성공/실패 모두) ──

	@Test
	@DisplayName("issue-token 성공: 활동로그를 SUCCESS로 기록한다")
	void issueToken_recordsSuccessActionLog() {
		controller().issueToken(new Cafe24AuthController.IssueTokenRequest("ABC123"));

		verify(actionLogService).record(eq(ActionLogConstants.CAFE24_AUTH), eq("CAFE24"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("issue-token 실패: 활동로그를 FAILED로 기록하고 500을 반환한다")
	void issueToken_recordsFailedActionLogAndReturns500() {
		doThrow(new RuntimeException("boom")).when(cafe24TokenManager).issueInitialToken(any());

		var response = controller().issueToken(new Cafe24AuthController.IssueTokenRequest("ABC123"));

		verify(actionLogService).record(eq(ActionLogConstants.CAFE24_AUTH), eq("CAFE24"),
			eq(ActionStatus.FAILED), any());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody().connected()).isFalse();
	}

	@Test
	@DisplayName("auth/callback: 활동로그를 남기지 않는다 (비대칭 보존)")
	void callback_doesNotRecordActionLog() {
		controller().handleCafe24AuthCode("ABC123");

		verify(actionLogService, never()).record(any(), any(), any(), any());
	}

	// ── 비대칭 2: 응답 형태·에러 처리 ──

	@Test
	@DisplayName("issue-token: 빈 코드는 400과 함께 토큰 발급을 건너뛴다")
	void issueToken_blankCodeReturns400() {
		var response = controller().issueToken(new Cafe24AuthController.IssueTokenRequest("   "));

		verify(cafe24TokenManager, never()).issueInitialToken(any());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().connected()).isFalse();
	}

	@Test
	@DisplayName("auth/callback 실패: 500과 실패 메시지(String)를 반환한다")
	void callback_failureReturns500String() {
		doThrow(new RuntimeException("boom")).when(cafe24TokenManager).issueInitialToken(any());

		var response = controller().handleCafe24AuthCode("ABC123");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).contains("인증 실패").contains("boom");
	}
}
