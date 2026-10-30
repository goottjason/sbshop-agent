package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sbshop.agent.core.application.dashboard.DashboardService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DashboardControllerAttentionClockTest {

	@Test
	@DisplayName("attention 기준 시각은 주문일과 같은 KST 벽시계다")
	void attentionUsesKstNow() {
		DashboardService service = mock(DashboardService.class);
		DashboardController controller = new DashboardController(service);
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			controller.attention();
		} finally {
			TimeZone.setDefault(original);
		}

		ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(service).attention(now.capture());
		LocalDateTime kstNow = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
		assertThat(Duration.between(now.getValue(), kstNow).abs()).isLessThan(Duration.ofMinutes(1));
	}
}
