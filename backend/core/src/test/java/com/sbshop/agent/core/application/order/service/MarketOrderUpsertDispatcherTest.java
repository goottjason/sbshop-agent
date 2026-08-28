package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.sync.SyncCounts;
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

	@Test
	@DisplayName("처리 건수와 신규 건수를 갈라서 돌려준다 — 0건 성공을 구분할 수 있어야 한다")
	void returnsProcessedAndCreatedCounts() {
		Order existing = Order.builder().marketOrderNo("O-A").build();
		when(orderRepository.findByMarketOrderNo("O-A")).thenReturn(Optional.of(existing));
		when(orderRepository.findByMarketOrderNo("O-B")).thenReturn(Optional.empty());
		when(orderRepository.findByMarketOrderNo("O-C")).thenReturn(Optional.empty());

		SyncCounts counts = MarketOrderUpsertDispatcher.dispatch(
			List.of(dto("O-A"), dto("O-B"), dto("O-C")), orderRepository, "TEST",
			(order, dtoArg) -> {},
			d -> {});

		assertThat(counts.processed()).isEqualTo(3);
		assertThat(counts.created()).isEqualTo(2);
	}

	@Test
	@DisplayName("갱신 전용 모드에서 없는 주문은 처리 건수에도 신규 건수에도 세지 않는다")
	void updateOnlyMode_doesNotCountSkipped() {
		when(orderRepository.findByMarketOrderNo("O-A")).thenReturn(Optional.empty());

		SyncCounts counts = MarketOrderUpsertDispatcher.dispatch(
			List.of(dto("O-A")), orderRepository, "TEST",
			(order, dtoArg) -> {},
			d -> {},
			false);

		assertThat(counts.processed()).isZero();
		assertThat(counts.created()).isZero();
	}

	private MarketOrderDto dto(String orderNo) {
		return MarketOrderDto.builder().marketOrderNo(orderNo).build();
	}
}
