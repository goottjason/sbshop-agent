package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * R1 / F-CAFE-2: {@code GET /status}의 HTTP 시맨틱을 특성화한다.
 *
 * <p><b>계약(비파괴):</b> "토큰 만료/무효·권한 없음"은 <em>정상 상태 결과</em>이므로
 * 반드시 {@code 200 + body(connected=false)}로 유지한다(프론트가 body를 읽어 표시).
 * <p><b>결함(F-CAFE-2):</b> 반면 <em>진짜 서버/인프라 오류</em>(Cafe24 미도달·타임아웃·5xx 등)를
 * 정상 status 결과인 양 200으로 감싸면 HTTP로 오류를 구분할 수 없다 → 예외를 전파해 5xx가 되게 한다.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24AuthControllerStatusTest {

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

	// ─────────────────────── 정상 상태(200 유지) 계약 ───────────────────────

	@Test
	@DisplayName("리프레시 토큰 없음: 200 + connected=false (정상 미연동 상태)")
	void noRefreshToken_returns200() {
		when(cafe24TokenManager.isRefreshTokenPresent()).thenReturn(false);

		var response = controller().status();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().connected()).isFalse();
	}

	@Test
	@DisplayName("토큰 만료/무효(재인증 필요): 200 + connected=false 유지 (프론트 계약)")
	void tokenExpired_returns200() {
		when(cafe24TokenManager.isRefreshTokenPresent()).thenReturn(true);
		// 토큰 매니저가 만료/무효를 알림 — RestClient는 401 등을 이 메시지로 감싼다.
		when(cafe24RestClient.get(anyString()))
			.thenThrow(new RuntimeException(
				"Cafe24 API 호출 실패: Cafe24 토큰 갱신 실패 — 재인증이 필요합니다"));

		var response = controller().status();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().connected()).isFalse();
	}

	@Test
	@DisplayName("상품 401(인증 만료): 200 + connected=false 유지")
	void product401_returns200() {
		when(cafe24TokenManager.isRefreshTokenPresent()).thenReturn(true);
		when(cafe24RestClient.get(anyString()))
			.thenThrow(new RuntimeException("Cafe24 API 호출 실패(401): invalid_token"));

		var response = controller().status();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().connected()).isFalse();
	}

	@Test
	@DisplayName("주문 권한 없음(insufficient_scope/403): 200 + connected=false 유지")
	void orderInsufficientScope_returns200() {
		when(cafe24TokenManager.isRefreshTokenPresent()).thenReturn(true);
		lenient().when(cafe24RestClient.get(anyString())).thenReturn("{}");
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), anyInt(), anyInt()))
			.thenThrow(new RuntimeException("403 insufficient_scope: mall.read_order"));

		var response = controller().status();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().connected()).isFalse();
		assertThat(response.getBody().message()).contains("권한");
	}

	@Test
	@DisplayName("정상 연동: 200 + connected=true")
	void healthy_returns200Connected() {
		when(cafe24TokenManager.isRefreshTokenPresent()).thenReturn(true);
		when(cafe24RestClient.get(anyString())).thenReturn("{}");
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), anyInt(), anyInt()))
			.thenReturn(JsonNodeFactory.instance.arrayNode());

		var response = controller().status();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().connected()).isTrue();
	}

	// ─────────────────── 진짜 인프라 오류(전파 → 5xx) — F-CAFE-2 ───────────────────

	@Test
	@DisplayName("F-CAFE-2: 상품 점검 중 진짜 인프라 오류(Cafe24 미도달)는 200으로 감싸지 않고 전파한다")
	void productInfraError_propagates() {
		when(cafe24TokenManager.isRefreshTokenPresent()).thenReturn(true);
		when(cafe24RestClient.get(anyString()))
			.thenThrow(new RuntimeException(
				"Cafe24 API 호출 실패: I/O error on GET request: Connection refused"));

		assertThatThrownBy(() -> controller().status())
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("F-CAFE-2: 상품 점검 중 5xx(Cafe24 서버 오류)는 200으로 감싸지 않고 전파한다")
	void product5xx_propagates() {
		when(cafe24TokenManager.isRefreshTokenPresent()).thenReturn(true);
		when(cafe24RestClient.get(anyString()))
			.thenThrow(new RuntimeException("Cafe24 API 호출 실패(503): Service Unavailable"));

		assertThatThrownBy(() -> controller().status())
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("F-CAFE-2: 주문 점검 중 진짜 인프라 오류(비-권한)는 200으로 감싸지 않고 전파한다")
	void orderInfraError_propagates() {
		when(cafe24TokenManager.isRefreshTokenPresent()).thenReturn(true);
		when(cafe24RestClient.get(anyString())).thenReturn("{}");
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), anyInt(), anyInt()))
			.thenThrow(new RuntimeException(
				"Cafe24 API 호출 실패: I/O error on GET request: Read timed out"));

		assertThatThrownBy(() -> controller().status())
			.isInstanceOf(RuntimeException.class);
	}
}
