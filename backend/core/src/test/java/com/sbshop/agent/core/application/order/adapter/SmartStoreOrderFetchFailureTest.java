package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * D-043: 스마트스토어 fetchOrders가 API 실패를 "성공 0건"으로 삼키지 않고 전량 실패 시 예외를 전파하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreOrderFetchFailureTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private SmartStoreOrderApiPort smartStoreOrderApiPort;
	@Mock
	private SmartStoreStatusMapper statusMapper;
	@InjectMocks
	private SmartStoreOrderAdapter adapter;

	@org.junit.jupiter.api.BeforeEach
	void speedUpDelays() {
		// D-118로 청크 간 지연이 생겼다 — 단위 테스트는 대기 없이 돌린다.
		adapter.chunkDelayMillis = 0L;
		adapter.retryBackoffMillis = 0L;
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("secret");
		return c;
	}

	@Test
	@DisplayName("전량 실패(모든 chunk API 오류): 빈 리스트가 아니라 예외를 전파")
	void allFail_throws() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("스마트스토어 주문 조회 HTTP 오류: 401"));

		// 어댑터는 endDate=now(UTC), startDate=fromDate.atStartOfDay(UTC)로 chunk를 만든다. 로컬 머신이
		// KST면 LocalDate.now()가 UTC보다 하루 앞서(자정~오전9시) startDate>endDate가 되어 chunk 0개 →
		// 예외 미발생으로 오탐한다. 어댑터의 UTC 기준에 맞춰 UTC 오늘 날짜를 넘겨 항상 1개 이상 chunk가 생기게 한다.
		LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
		assertThatThrownBy(() -> adapter.fetchOrders(credential(), today, today))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("스마트스토어 주문 조회 실패");
	}

	@Test
	@DisplayName("진짜 0건(예외 없는 빈 응답): 예외 없이 빈 리스트 반환")
	void genuineEmpty_returnsEmptyWithoutThrow() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenReturn(MAPPER.createArrayNode());

		LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
		assertThat(adapter.fetchOrders(credential(), today, today)).isEmpty();
	}
}
