package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketOrderUpsertDispatcherTest {
	@Mock
	private OrderRepository orderRepository;

	@Test
	@DisplayName("기존 주문이 있으면 update 콜백에 (기존 Order, dto)로 위임한다")
	void dispatchesToUpdate_whenOrderExists() {
		Order existing = Order.builder().marketOrderNo("O-1").build();
		when(orderRepository.findByMarketOrderNo("O-1")).thenReturn(Optional.of(existing));
		MarketOrderDto d = dto("O-1");

		List<Order> updated = new ArrayList<>();
		List<MarketOrderDto> created = new ArrayList<>();

		MarketOrderUpsertDispatcher.dispatch(
			List.of(d), orderRepository, "TEST",
			(order, dtoArg) -> updated.add(order),
			created::add);

		assertThat(updated).containsExactly(existing);
		assertThat(created).isEmpty();
	}

	@Test
	@DisplayName("기존 주문이 없으면 create 콜백에 dto로 위임한다")
	void dispatchesToCreate_whenOrderAbsent() {
		when(orderRepository.findByMarketOrderNo("O-2")).thenReturn(Optional.empty());
		MarketOrderDto d = dto("O-2");

		List<Order> updated = new ArrayList<>();
		List<MarketOrderDto> created = new ArrayList<>();

		MarketOrderUpsertDispatcher.dispatch(
			List.of(d), orderRepository, "TEST",
			(order, dtoArg) -> updated.add(order),
			created::add);

		assertThat(created).containsExactly(d);
		assertThat(updated).isEmpty();
	}

	@Test
	@DisplayName("여러 주문을 입력 순서대로 각각 판정해 분기한다")
	void dispatchesEachOrderInOrder() {
		Order existing = Order.builder().marketOrderNo("O-A").build();
		when(orderRepository.findByMarketOrderNo("O-A")).thenReturn(Optional.of(existing));
		when(orderRepository.findByMarketOrderNo("O-B")).thenReturn(Optional.empty());
		MarketOrderDto a = dto("O-A");
		MarketOrderDto b = dto("O-B");

		List<Order> updated = new ArrayList<>();
		List<MarketOrderDto> created = new ArrayList<>();

		MarketOrderUpsertDispatcher.dispatch(
			List.of(a, b), orderRepository, "TEST",
			(order, dtoArg) -> updated.add(order),
			created::add);

		assertThat(updated).containsExactly(existing);
		assertThat(created).containsExactly(b);
	}

	private MarketOrderDto dto(String orderNo) {
		return MarketOrderDto.builder().marketOrderNo(orderNo).build();
	}
}
