package com.sbshop.agent.api.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.market.inspection.*;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DailyMarketInspectionControllerTest {
	private final DailyMarketInspectionService daily = mock(DailyMarketInspectionService.class);
	private final MarketInspectionService selected = mock(MarketInspectionService.class);
	private final MockMvc mvc = MockMvcBuilders
		.standaloneSetup(new DailyMarketInspectionController(daily), new MarketInspectionController(selected))
		.setControllerAdvice(new GlobalExceptionHandler()).build();
	private final String root = "/api/v1/products/connection-inspections/daily";

	@Test void statusHasScheduleAndAccountHoldWithoutStartingWork() throws Exception {
        when(daily.status()).thenReturn(new DailyMarketInspectionService.DailyStatus(true, false, "매일 03:00 (한국시간)", null, "계정 보류", null));
        mvc.perform(get(root)).andExpect(status().isOk()).andExpect(jsonPath("$.accountVerified").value(false))
            .andExpect(jsonPath("$.schedule").value("매일 03:00 (한국시간)"));
        verify(daily).status();
        verifyNoMoreInteractions(daily);
        verifyNoInteractions(selected);
    }

	@Test void fullDailyBatchListCanBeOpenedAndMissingSweepIs404() throws Exception {
        when(daily.batches("day")).thenReturn(List.of(new MarketInspectionService.BatchView("batch", "system", "DAILY", Instant.now(), 500, 490, 8, 1, 1, 0, List.of())));
        mvc.perform(get(root + "/day/batches")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].source").value("DAILY")).andExpect(jsonPath("$[0].needsAttention").value(1));
        when(daily.batches("missing")).thenThrow(new ResourceNotFoundException("missing"));
        mvc.perform(get(root + "/missing/batches")).andExpect(status().isNotFound());
        verifyNoInteractions(selected);
    }
}
