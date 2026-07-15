package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

/**
 * F-SYNC-5 구조 리팩토링 회귀 방지: DTO 기반 주문 sync 3서비스(쿠팡/스마트스토어/11번가)가 공유하는
 * "주문 목록을 순회하며 marketOrderNo로 기존/신규를 판정해 update-or-create로 분기"하는 골격을
 * {@link MarketOrderUpsertDispatcher}로 추출했다. 이 헬퍼는 상태를 갖지 않으며, 분기 결정만 수행하고
 * 실제 update/create는 각 서비스의 콜백에 위임한다(마켓별 파싱·필드 매핑은 통합하지 않음).
 */
@ExtendWith(MockitoExtension.class)
class MarketOrderUpsertDispatcherTest {

	@Mock private OrderRepository orderRepository;

	private MarketOrderDto dto(String orderNo) {
		return MarketOrderDto.builder().marketOrderNo(orderNo).build();
	}

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
}
