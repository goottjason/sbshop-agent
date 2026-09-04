package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.actionlog.repository.ActionLogRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaimOrphanRecorderTest {

	@Mock
	private ActionLogService actionLogService;
	@Mock
	private ActionLogRepository actionLogRepository;

	private ClaimOrphanRecorder recorder() {
		return new ClaimOrphanRecorder(actionLogService, actionLogRepository);
	}

	@Test
	@DisplayName("D-279: 짝 없는 주문번호를 활동로그에 남긴다 — 로그는 배포마다 날아가서 규모를 못 잰다")
	void recordsOrphansToActionLog() {
		when(actionLogRepository.countTodayByActionType(anyString(), any())).thenReturn(0L);

		recorder().record(MarketType.ELEVEN_STREET, List.of("20260807090911423"));

		ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
		verify(actionLogService).record(eq("ELEVEN_STREET_CLAIM_ORPHAN"), eq("ELEVEN_STREET"),
			eq(ActionStatus.WARNING), msg.capture());
		assertThat(msg.getValue()).contains("20260807090911423");
	}

	@Test
	@DisplayName("D-279: 같은 날 이미 남겼으면 다시 남기지 않는다 — 30분마다 도는 동기화가 활동로그를 덮지 않게")
	void doesNotRepeatWithinSameDay() {
		when(actionLogRepository.countTodayByActionType(anyString(), any())).thenReturn(1L);

		recorder().record(MarketType.ELEVEN_STREET, List.of("20260807090911423"));

		verify(actionLogService, never()).record(anyString(), anyString(), any(), anyString());
	}

	@Test
	@DisplayName("D-279: 짝 없는 건이 없으면 아무것도 남기지 않는다")
	void recordsNothingWhenNoOrphans() {
		recorder().record(MarketType.COUPANG, List.of());

		verify(actionLogService, never()).record(anyString(), anyString(), any(), anyString());
	}

	@Test
	@DisplayName("D-279: 마켓별로 따로 센다 — 쿠팡과 11번가가 서로의 기록을 덮지 않는다")
	void countsPerMarket() {
		when(actionLogRepository.countTodayByActionType(eq("COUPANG_CLAIM_ORPHAN"), any())).thenReturn(0L);

		recorder().record(MarketType.COUPANG, Set.of("A1"));

		verify(actionLogService).record(eq("COUPANG_CLAIM_ORPHAN"), eq("COUPANG"),
			eq(ActionStatus.WARNING), anyString());
	}

	@Test
	@DisplayName("D-279: 건수를 메시지에 담는다 — 규모가 바로 읽혀야 한다")
	void messageCarriesCount() {
		when(actionLogRepository.countTodayByActionType(anyString(), any())).thenReturn(0L);

		recorder().record(MarketType.COUPANG, List.of("A1", "A2", "A3"));

		ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
		verify(actionLogService).record(anyString(), anyString(), any(), msg.capture());
		assertThat(msg.getValue()).contains("3건");
	}
}
