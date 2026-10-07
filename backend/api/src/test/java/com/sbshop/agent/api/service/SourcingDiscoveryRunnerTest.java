package com.sbshop.agent.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingDiscoveryUseCase;
import com.sbshop.agent.core.application.sourcing.dto.DiscoverySummary;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourcingDiscoveryRunnerTest {
    @Test
    void failedNewRunCannotExposeThePreviousSuccessfulSummary() {
        var discovery = mock(SourcingDiscoveryUseCase.class);
        var logs = mock(ActionLogService.class);
        var runner = new SourcingDiscoveryRunner(discovery, logs);
        var success = new DiscoverySummary(LocalDateTime.now(), LocalDateTime.now(),
            10, 10, 0, 0, 10, 0, 0, 0, List.of());
        when(discovery.run()).thenReturn(success).thenThrow(new IllegalStateException("crawler unavailable"));
        runner.tryStart();
        runner.runAsync();
        verify(logs).record(anyString(), isNull(), eq(ActionStatus.SUCCESS), anyString());
        runner.tryStart();
        runner.runAsync();
        assertThat(runner.lastSummary().crawled()).isZero();
        assertThat(runner.lastSummary().warnings()).contains("crawler unavailable");
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void failedCrawlIsRecordedAsFailureAndReleasesRunningState() {
        var discovery = mock(SourcingDiscoveryUseCase.class);
        var logs = mock(ActionLogService.class);
        var runner = new SourcingDiscoveryRunner(discovery, logs);
        when(discovery.run()).thenReturn(DiscoverySummary.failed(LocalDateTime.now(), List.of("request timed out")));
        assertThat(runner.tryStart()).isTrue();
        runner.runAsync();
        verify(logs).record(anyString(), isNull(), eq(ActionStatus.FAILED), contains("request timed out"));
        verify(logs, never()).record(anyString(), isNull(), eq(ActionStatus.SUCCESS), anyString());
        assertThat(runner.isRunning()).isFalse();
        assertThat(runner.lastSummary().crawled()).isZero();
    }

    @Test
    void startLogFailureDoesNotPermanentlyLockDiscovery() {
        var discovery = mock(SourcingDiscoveryUseCase.class);
        var logs = mock(ActionLogService.class);
        var runner = new SourcingDiscoveryRunner(discovery, logs);
        doThrow(new IllegalStateException("log storage unavailable")).when(logs)
            .record(anyString(), isNull(), eq(ActionStatus.STARTED), anyString());
        runner.tryStart();
        try { runner.runAsync(); } catch (RuntimeException ignored) { }
        assertThat(runner.isRunning()).isFalse();
    }
}
