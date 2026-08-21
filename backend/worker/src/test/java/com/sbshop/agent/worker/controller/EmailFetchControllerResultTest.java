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

@ExtendWith(MockitoExtension.class)
class EmailFetchControllerResultTest {

	@Mock
	EmailFetcherService emailFetcherService;

	@Mock
	SyncStatusService syncStatusService;

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

	private EmailFetchController controller(String configuredToken) {
		return new EmailFetchController(emailFetcherService,
			new InternalAccessGuard(configuredToken), syncStatusService);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(ResponseEntity<?> res) {
		return (Map<String, Object>)res.getBody();
	}
}
