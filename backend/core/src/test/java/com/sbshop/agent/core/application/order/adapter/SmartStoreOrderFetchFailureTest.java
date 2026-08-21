package com.sbshop.agent.core.application.order.adapter;

import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
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

	@BeforeEach
	void speedUpDelays() {
		adapter.chunkDelayMillis = 0L;
		adapter.retryBackoffMillis = 0L;
	}

	@Test
	@DisplayName("전량 실패(모든 chunk API 오류): 빈 리스트가 아니라 예외를 전파")
	void allFail_throws() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("스마트스토어 주문 조회 HTTP 오류: 401"));

		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		assertThatThrownBy(() -> adapter.fetchOrders(credential(), today, today))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("스마트스토어 주문 조회 실패");
	}

	@Test
	@DisplayName("진짜 0건(예외 없는 빈 응답): 예외 없이 빈 리스트 반환")
	void genuineEmpty_returnsEmptyWithoutThrow() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenReturn(MAPPER.createArrayNode());

		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		assertThat(adapter.fetchOrders(credential(), today, today)).isEmpty();
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("secret");
		return c;
	}
}
