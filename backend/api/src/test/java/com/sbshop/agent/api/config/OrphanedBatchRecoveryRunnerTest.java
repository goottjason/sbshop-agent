package com.sbshop.agent.api.config;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrphanedBatchRecoveryRunnerTest {

	@Mock
	private ProcessStatusService processStatusService;
	@InjectMocks
	private OrphanedBatchRecoveryRunner runner;

	@Test
	@DisplayName("부팅 시 recoverOrphanedPending을 1회 호출한다")
	void recoverOnStartup_invokesService() {
		when(processStatusService.recoverOrphanedPending()).thenReturn(3);

		runner.recoverOnStartup();

		verify(processStatusService).recoverOrphanedPending();
	}
}
