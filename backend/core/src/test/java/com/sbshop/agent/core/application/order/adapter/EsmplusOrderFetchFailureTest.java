package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.mapper.EsmplusStatusMapper;
import com.sbshop.agent.core.application.order.port.EsmplusOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import java.time.LocalDate;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * D-043: ESM+ fetchOrders가 스크래핑 실패를 "성공 0건"으로 삼키지 않고 예외를 전파하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EsmplusOrderFetchFailureTest {

	@Mock
	private EsmplusOrderApiPort esmplusOrderApiPort;
	@Mock
	private EsmplusStatusMapper statusMapper;
	@InjectMocks
	private EsmplusOrderAdapter adapter;

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getAccessKey()).thenReturn("masterId");
		when(c.getSecretKey()).thenReturn("password");
		return c;
	}

	@Test
	@DisplayName("스크래핑 실패: 빈 리스트가 아니라 예외를 전파")
	void scrapeFail_throws() {
		when(esmplusOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("ESM+ 스크래핑 실패: 로그인 실패"));

		assertThatThrownBy(() -> adapter.fetchOrders(credential(),
			LocalDate.now(), LocalDate.now()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("ESM+ 주문 조회 실패");
	}

	@Test
	@DisplayName("진짜 0건(예외 없는 빈 응답): 예외 없이 빈 리스트 반환")
	void genuineEmpty_returnsEmptyWithoutThrow() {
		when(esmplusOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenReturn(Collections.emptyList());

		assertThat(adapter.fetchOrders(credential(), LocalDate.now(), LocalDate.now())).isEmpty();
	}

	@Test
	@DisplayName("masterId 부재: 예외 전파(빈 반환 아님)")
	void missingMasterId_throws() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getAccessKey()).thenReturn("");

		assertThatThrownBy(() -> adapter.fetchOrders(c, LocalDate.now(), LocalDate.now()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("masterId");
	}
}
