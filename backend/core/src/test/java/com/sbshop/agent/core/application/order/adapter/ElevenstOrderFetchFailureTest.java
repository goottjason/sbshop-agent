package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.w3c.dom.Element;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstOrderFetchFailureTest {
	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;
	@Mock
	private ElevenstStatusMapper statusMapper;
	@InjectMocks
	private ElevenstOrderAdapter adapter;

	@Test
	@DisplayName("전량 실패(모든 chunk API 오류): 빈 리스트가 아니라 예외를 전파")
	void allFail_throws() {
		when(elevenstOrderApiPort.fetchCompletedOrders(any(), any(), any()))
			.thenThrow(new RuntimeException("11번가 API 요청 실패: 403"));

		assertThatThrownBy(() -> adapter.fetchOrders(credential(),
			LocalDate.now(), LocalDate.now()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("11번가 주문 조회 실패");
	}

	@Test
	@DisplayName("진짜 0건(예외 없는 빈 응답): 예외 없이 빈 리스트 반환")
	void genuineEmpty_returnsEmptyWithoutThrow() {
		List<Element> empty = Collections.emptyList();
		when(elevenstOrderApiPort.fetchCompletedOrders(any(), any(), any())).thenReturn(empty);
		when(elevenstOrderApiPort.fetchPackagingOrders(any(), any(), any())).thenReturn(empty);
		when(elevenstOrderApiPort.fetchShippingOrders(any(), any(), any())).thenReturn(empty);
		when(elevenstOrderApiPort.fetchCompletedDeliveryOrders(any(), any(), any())).thenReturn(empty);

		assertThat(adapter.fetchOrders(credential(), LocalDate.now(), LocalDate.now())).isEmpty();
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getAccessKey()).thenReturn("apikey");
		return c;
	}
}
