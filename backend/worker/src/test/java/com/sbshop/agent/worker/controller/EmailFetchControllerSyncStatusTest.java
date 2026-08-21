package com.sbshop.agent.worker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.config.InternalAccessGuard;
import com.sbshop.agent.worker.service.EmailFetcherService;

@ExtendWith(MockitoExtension.class)
class EmailFetchControllerSyncStatusTest {

	@Mock
	EmailFetcherService emailFetcherService;

	@Mock
	SyncStatusService syncStatusService;

	@Test
	@DisplayName("수동 트리거 성공 → markRunning(EMAIL) 후 markCompleted(EMAIL) 순서로 기록")
	void success_recordsRunningThenCompleted() {
		when(emailFetcherService.fetchAndProcessEmails()).thenReturn(true);

		ResponseEntity<?> res = controller("").fetch(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		InOrder inOrder = inOrder(syncStatusService, emailFetcherService);
		inOrder.verify(syncStatusService).markRunning(SyncMarketKeys.EMAIL);
		inOrder.verify(emailFetcherService).fetchAndProcessEmails();
		inOrder.verify(syncStatusService).markCompleted(SyncMarketKeys.EMAIL);
	}

	@Test
	@DisplayName("수동 트리거 실패 → markRunning(EMAIL) 후 markFailed(EMAIL, 사유) 기록, markCompleted 미기록")
	void failure_recordsRunningThenFailed() {
		doThrow(new RuntimeException("boom")).when(emailFetcherService).fetchAndProcessEmails();

		ResponseEntity<?> res = controller("").fetch(null);

		assertThat(res.getStatusCode().is5xxServerError()).isTrue();
		InOrder inOrder = inOrder(syncStatusService, emailFetcherService);
		inOrder.verify(syncStatusService).markRunning(SyncMarketKeys.EMAIL);
		inOrder.verify(emailFetcherService).fetchAndProcessEmails();
		inOrder.verify(syncStatusService).markFailed(eq(SyncMarketKeys.EMAIL), contains("boom"));
		verify(syncStatusService, never()).markCompleted(SyncMarketKeys.EMAIL);
	}

	@Test
	@DisplayName("재진입 가드로 스킵(false) → markRunning만 기록, markCompleted 미기록")
	void skipped_doesNotRecordCompleted() {
		when(emailFetcherService.fetchAndProcessEmails()).thenReturn(false);

		ResponseEntity<?> res = controller("").fetch(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(syncStatusService).markRunning(SyncMarketKeys.EMAIL);
		verify(syncStatusService, never()).markCompleted(SyncMarketKeys.EMAIL);
	}

	@Test
	@DisplayName("가드 차단(403) → 서비스·상태기록 모두 미실행")
	void forbidden_recordsNothing() {
		ResponseEntity<?> res = controller("s3cr3t").fetch(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		verify(emailFetcherService, never()).fetchAndProcessEmails();
		verify(syncStatusService, never()).markRunning(SyncMarketKeys.EMAIL);
	}

	private EmailFetchController controller(String configuredToken) {
		return new EmailFetchController(emailFetcherService,
			new InternalAccessGuard(configuredToken), syncStatusService);
	}
}
