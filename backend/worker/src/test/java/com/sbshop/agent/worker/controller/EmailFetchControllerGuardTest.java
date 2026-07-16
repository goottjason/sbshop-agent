package com.sbshop.agent.worker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
 * F-MISC-17: /internal/email/fetch 무인증 트리거에 공유시크릿 헤더 가드 적용.
 * 가드 활성 시 토큰 불일치/누락은 403 + 서비스 미실행, 일치하면 실행.
 * 가드 비활성(토큰 미설정)이면 헤더 없이도 실행(무파손).
 */
@ExtendWith(MockitoExtension.class)
class EmailFetchControllerGuardTest {

	@Mock
	EmailFetcherService emailFetcherService;

	@Mock
	SyncStatusService syncStatusService;

	private EmailFetchController controller(String configuredToken) {
		return new EmailFetchController(emailFetcherService,
			new InternalAccessGuard(configuredToken), syncStatusService);
	}

	@Test
	@DisplayName("가드 활성 + 헤더 누락 → 403, 서비스 미실행")
	void enabled_missingHeader_forbidden() {
		ResponseEntity<?> res = controller("s3cr3t").fetch(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		verify(emailFetcherService, never()).fetchAndProcessEmails();
	}

	@Test
	@DisplayName("가드 활성 + 헤더 불일치 → 403, 서비스 미실행")
	void enabled_mismatch_forbidden() {
		ResponseEntity<?> res = controller("s3cr3t").fetch("wrong");

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		verify(emailFetcherService, never()).fetchAndProcessEmails();
	}

	@Test
	@DisplayName("가드 활성 + 헤더 일치 → 200, 서비스 실행")
	void enabled_match_ok() {
		ResponseEntity<?> res = controller("s3cr3t").fetch("s3cr3t");

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(emailFetcherService).fetchAndProcessEmails();
	}

	@Test
	@DisplayName("가드 비활성(토큰 미설정) + 헤더 없음 → 200, 서비스 실행(무파손)")
	void disabled_noHeader_ok() {
		ResponseEntity<?> res = controller("").fetch(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(emailFetcherService).fetchAndProcessEmails();
	}
}
