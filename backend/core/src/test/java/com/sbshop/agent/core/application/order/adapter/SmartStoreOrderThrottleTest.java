package com.sbshop.agent.core.application.order.adapter;

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

/**
 * D-118: 네이버 API 429(TOO_MANY_REQUESTS)로 날짜 청크가 통째로 유실되던 문제.
 * 청크 간 간격을 성공·실패 무관하게 두고, 429는 백오프 재시도로 회수한다.
 */
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
		// 테스트에서는 실제 대기 없이 재시도 횟수·회수 동작만 검증한다.
		adapter.chunkDelayMillis = 0L;
		adapter.retryBackoffMillis = 0L;
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("secret");
		return c;
	}

	private LocalDate utcToday() {
		return LocalDate.now(java.time.ZoneOffset.UTC);
	}

	@Test
	@DisplayName("D-118: 429가 났다가 재시도에서 성공하면 그 청크는 유실되지 않는다")
	void retriesAfterTooManyRequests() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("스마트스토어 주문 조회 HTTP 오류: 429 TOO_MANY_REQUESTS"))
			.thenReturn(MAPPER.createArrayNode());

		adapter.fetchOrders(credential(), utcToday(), utcToday());

		// 최초 1회 + 재시도 1회 = 2회 호출되어야 회수된다(재시도 없으면 1회에 그침).
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
}
