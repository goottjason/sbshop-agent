package com.sbshop.agent.core.application.order.adapter;

import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreOrderThrottleTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private SmartStoreOrderApiPort smartStoreOrderApiPort;
	@Mock
	private SmartStoreStatusMapper statusMapper;
	@InjectMocks
	private SmartStoreOrderAdapter adapter;

	@BeforeEach
	void speedUpDelays() {
		adapter.chunkDelayMillis = 0L;
		adapter.retryBackoffMillis = 0L;
	}

	@Test
	@DisplayName("D-118: 429가 났다가 재시도에서 성공하면 그 청크는 유실되지 않는다")
	void retriesAfterTooManyRequests() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("스마트스토어 주문 조회 HTTP 오류: 429 TOO_MANY_REQUESTS"))
			.thenReturn(MAPPER.createArrayNode());

		adapter.fetchOrders(credential(), utcToday(), utcToday());

		verify(smartStoreOrderApiPort, times(2)).fetchOrders(any(), any(), any(), any());
	}

	@Test
	@DisplayName("D-118: 재시도를 모두 소진해도 전량 실패면 기존대로 예외를 전파한다")
	void exhaustedRetries_stillPropagates() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("스마트스토어 주문 조회 HTTP 오류: 429 TOO_MANY_REQUESTS"));

		assertThatThrownBy(() -> adapter.fetchOrders(credential(), utcToday(), utcToday()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("스마트스토어 주문 조회 실패");
	}

	@Test
	@DisplayName("D-118: 429가 아닌 오류(401 등)는 재시도하지 않는다 — 재시도해도 소용없고 폭주만 키운다")
	void nonRateLimitError_doesNotRetry() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("스마트스토어 주문 조회 HTTP 오류: 401 UNAUTHORIZED"));

		assertThatThrownBy(() -> adapter.fetchOrders(credential(), utcToday(), utcToday()))
			.isInstanceOf(RuntimeException.class);

		verify(smartStoreOrderApiPort, times(1)).fetchOrders(any(), any(), any(), any());
	}

	@Test
	@DisplayName("D-118: 정상 응답이면 재시도 없이 1회만 호출한다")
	void success_callsOnce() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenReturn(MAPPER.createArrayNode());

		assertThat(adapter.fetchOrders(credential(), utcToday(), utcToday())).isEmpty();

		verify(smartStoreOrderApiPort, times(1)).fetchOrders(any(), any(), any(), any());
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("secret");
		return c;
	}

	private LocalDate utcToday() {
		return LocalDate.now(ZoneOffset.UTC);
	}
}
