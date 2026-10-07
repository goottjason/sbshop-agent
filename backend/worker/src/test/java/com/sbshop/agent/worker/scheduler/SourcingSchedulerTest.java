package com.sbshop.agent.worker.scheduler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.sourcing.customs.BannedIngredientSyncService;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingConfigService;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingDiscoveryUseCase;
import com.sbshop.agent.core.application.sourcing.dto.DiscoverySummary;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SourcingSchedulerTest {
    @Test
    void failedScheduledCrawlIsRecordedAsFailure() {
        var discovery = mock(SourcingDiscoveryUseCase.class);
        var config = mock(SourcingConfigService.class);
        var logs = mock(ActionLogService.class);
        var scheduler = new SourcingScheduler(discovery, mock(BannedIngredientSyncService.class), config, logs);
        ReflectionTestUtils.setField(scheduler, "schedulerEnabled", true);
        when(config.getOrCreate()).thenReturn(SourcingConfig.createDefault());
        when(discovery.run()).thenReturn(DiscoverySummary.failed(LocalDateTime.now(), List.of("request timed out")));
        scheduler.runDiscovery();
        verify(logs).record(anyString(), isNull(), eq(ActionStatus.FAILED), contains("request timed out"));
        verify(logs, never()).record(anyString(), isNull(), eq(ActionStatus.SUCCESS), anyString());
    }
}
