package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-041 재현/회귀: 쿠팡 fetchOrders가 API 오류(403 등)를 삼켜 "성공 0건"으로 위장하던 결함을 고정한다.
 * 전량 실패(모든 status 오류)면 상태코드를 담은 예외를 전파해야 하며,
 * 진짜 0건(예외 없는 빈 응답)은 예외 없이 빈 리스트를 반환해야 한다(구분).
 */
@ExtendWith(MockitoExtension.class)
class CoupangOrderFetchFailureTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private CoupangOrderApiPort coupangOrderApiPort;
	@Mock
	private CoupangStatusMapper statusMapper;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@InjectMocks
	private CoupangOrderAdapter adapter;

	private MarketCredential credential() {
		return mock(MarketCredential.class);
	}

	/** orderItems 없는 최소 주문 노드 배열 (parseOrderNode가 상품조회 없이 파싱 가능) */
	private ArrayNode singleOrderArray() {
		ObjectNode order = MAPPER.createObjectNode();
		order.put("orderId", "1001");
		order.put("orderedAt", "2026-07-01T10:00:00");
		order.putObject("receiver").put("name", "홍길동");
		ArrayNode arr = MAPPER.createArrayNode();
		arr.add(order);
		return arr;
	}

	@Test
	@DisplayName("전량 실패(모든 status 403): 빈 리스트가 아니라 상태코드 포함 예외를 전파")
	void allStatusFail_throwsWithStatusCode() {
		when(coupangOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenThrow(new RuntimeException("쿠팡 API HTTP 오류: 403 FORBIDDEN (status=ACCEPT)"));

		assertThatThrownBy(() -> adapter.fetchOrders(credential(),
			LocalDate.now().minusDays(1), LocalDate.now()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("403");
	}

	@Test
	@DisplayName("진짜 0건(예외 없는 빈 응답): 예외 없이 빈 리스트 반환")
	void genuineEmpty_returnsEmptyWithoutThrow() {
		when(coupangOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenReturn(MAPPER.createArrayNode());

		List<MarketOrderDto> result = adapter.fetchOrders(credential(),
			LocalDate.now().minusDays(1), LocalDate.now());

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("정상 응답: 주문을 담아 반환")
	void success_returnsOrders() {
		lenient().when(statusMapper.mapStatus(any())).thenReturn(ShippingStatus.NEW);
		when(coupangOrderApiPort.fetchOrders(any(), any(), any(), eq("ACCEPT")))
			.thenReturn(singleOrderArray());
		when(coupangOrderApiPort.fetchOrders(any(), any(), any(),
			org.mockito.ArgumentMatchers.argThat(s -> !"ACCEPT".equals(s))))
			.thenReturn(MAPPER.createArrayNode());

		List<MarketOrderDto> result = adapter.fetchOrders(credential(),
			LocalDate.now().minusDays(1), LocalDate.now());

		assertThat(result).hasSize(1);
	}

	@Test
	@DisplayName("부분 실패(일부 status만 오류): 성공분을 반환하고 예외를 던지지 않음")
	void partialFailure_returnsSuccessfulWithoutThrow() {
		lenient().when(statusMapper.mapStatus(any())).thenReturn(ShippingStatus.NEW);
		when(coupangOrderApiPort.fetchOrders(any(), any(), any(), eq("ACCEPT")))
			.thenReturn(singleOrderArray());
		when(coupangOrderApiPort.fetchOrders(any(), any(), any(),
			org.mockito.ArgumentMatchers.argThat(s -> !"ACCEPT".equals(s))))
			.thenThrow(new RuntimeException("쿠팡 API HTTP 오류: 500"));

		List<MarketOrderDto> result = adapter.fetchOrders(credential(),
			LocalDate.now().minusDays(1), LocalDate.now());

		assertThat(result).hasSize(1);
	}
}
