package com.sbshop.agent.worker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.config.InternalAccessGuard;
import com.sbshop.agent.worker.service.EmailFetcherService;

/**
 * F-MISC-20: /internal/email/fetch 응답이 실제 처리 결과와 무관하게 항상 ok:true 를 반환한다.
 * 재진입 가드(F-MISC-18)로 서비스가 본처리를 스킵(이미 실행 중)해도 컨트롤러는 여전히
 * "실행됨"과 구별되지 않는 ok:true 를 돌려주어, 내부 트리거 호출자가 실제 실행 여부를 알 수 없다.
 * 응답 body 가 서비스의 실제 반환(실행 여부)을 반영해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class EmailFetchControllerResultTest {

	@Mock
	EmailFetcherService emailFetcherService;

	@Mock
	SyncStatusService syncStatusService;

	private EmailFetchController controller(String configuredToken) {
		return new EmailFetchController(emailFetcherService,
			new InternalAccessGuard(configuredToken), syncStatusService);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(ResponseEntity<?> res) {
		return (Map<String, Object>)res.getBody();
	}

	@Test
	@DisplayName("서비스가 실제 실행됨(true) → 200 + ok:true + executed:true")
	void executed_true_reflectedInBody() {
		when(emailFetcherService.fetchAndProcessEmails()).thenReturn(true);

		ResponseEntity<?> res = controller("").fetch(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(body(res)).containsEntry("ok", true);
		assertThat(body(res)).containsEntry("executed", true);
	}

	@Test
	@DisplayName("서비스가 스킵됨(false, 이미 실행 중) → 200 + ok:true + executed:false")
	void skipped_reflectedInBody() {
		when(emailFetcherService.fetchAndProcessEmails()).thenReturn(false);

		ResponseEntity<?> res = controller("").fetch(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(body(res)).containsEntry("ok", true);
		assertThat(body(res)).containsEntry("executed", false);
	}
}
