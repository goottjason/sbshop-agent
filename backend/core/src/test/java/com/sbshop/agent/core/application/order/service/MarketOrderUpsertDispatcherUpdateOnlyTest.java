package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketOrderUpsertDispatcherUpdateOnlyTest {
	@Test
	@DisplayName("갱신 전용이면 없는 주문을 만들지 않는다 — 기존 주문 갱신은 그대로 한다")
	void skipsCreationButStillUpdates() {
		OrderRepository repo = mock(OrderRepository.class);
		when(repo.findByMarketOrderNo("EXISTING")).thenReturn(Optional.of(existing()));
		when(repo.findByMarketOrderNo("MISSING")).thenReturn(Optional.empty());

		List<String> updated = new ArrayList<>();
		List<String> created = new ArrayList<>();

		MarketOrderUpsertDispatcher.dispatch(
			List.of(dto("EXISTING"), dto("MISSING")), repo, "COUPANG",
			(order, d) -> updated.add(d.getMarketOrderNo()),
			d -> created.add(d.getMarketOrderNo()),
			false);

		assertThat(updated).containsExactly("EXISTING");
		assertThat(created).isEmpty();
	}

	@Test
	@DisplayName("기본(정기 동기화)은 종전대로 없는 주문을 만든다 — 백필만 예외다")
	void defaultStillCreates() {
		OrderRepository repo = mock(OrderRepository.class);
		when(repo.findByMarketOrderNo(anyString())).thenReturn(Optional.empty());

		List<String> created = new ArrayList<>();

		MarketOrderUpsertDispatcher.dispatch(
			List.of(dto("NEW-1")), repo, "COUPANG",
			(order, d) -> {},
			d -> created.add(d.getMarketOrderNo()));

		assertThat(created).containsExactly("NEW-1");
	}

	private MarketOrderDto dto(String orderNo) {
		return MarketOrderDto.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo(orderNo)
			.orderDate(LocalDateTime.now())
			.build();
	}

	private Order existing() {
		return Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("EXISTING")
			.orderDate(LocalDateTime.now())
			.build();
	}
}
