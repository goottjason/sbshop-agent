package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24OrderAcceptanceTest {
	private static final String ORDER = "20261013-0000015";

	@Mock
	Cafe24OrderApiPort port;

	private Cafe24OrderAcceptance acceptance() {
		return new Cafe24OrderAcceptance(port, 3, ignored -> {
		});
	}

	private static JsonNode detail(String... statuses) {
		var node = new ObjectMapper().createObjectNode();
		var items = node.putArray("items");
		for (String s : statuses)
			items.addObject().put("order_status", s);
		return node;
	}

	@Test
	@DisplayName("D-346: 카페24에서 이미 배송준비중이면 다시 보내지 않고 성공으로 끝낸다")
	void alreadyPreparingOnCafe24_succeedsWithoutResending() {
		when(port.fetchOrderDetail(ORDER)).thenReturn(detail("N20"));

		assertThatCode(() -> acceptance().accept(ORDER)).doesNotThrowAnyException();

		verify(port, never()).acceptOrder(anyString());
	}

	@Test
	@DisplayName("D-346: 확인 요청 후 카페24 상태가 늦게 바뀌어도 바뀔 때까지 다시 읽고 성공한다")
	void delayedReflection_pollsUntilPreparing() {
		when(port.fetchOrderDetail(ORDER)).thenReturn(detail("N10"), detail("N10"), detail("N20"));

		assertThatCode(() -> acceptance().accept(ORDER)).doesNotThrowAnyException();

		verify(port, times(1)).acceptOrder(ORDER);
		verify(port, times(3)).fetchOrderDetail(ORDER);
	}

	@Test
	@DisplayName("D-346: 끝내 신규로 남으면 아직 반영되지 않았다고 알린다")
	void neverReflected_reportsNotYetApplied() {
		when(port.fetchOrderDetail(ORDER)).thenReturn(detail("N10"));

		assertThatThrownBy(() -> acceptance().accept(ORDER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("아직");

		verify(port, times(1)).acceptOrder(ORDER);
		verify(port, times(4)).fetchOrderDetail(ORDER);
	}

	@Test
	@DisplayName("D-346: 사전 조회가 실패해도 확인 요청은 보낸다")
	void precheckFailure_stillSendsAccept() {
		when(port.fetchOrderDetail(ORDER)).thenThrow(new RuntimeException("timeout")).thenReturn(detail("N20"));

		assertThatCode(() -> acceptance().accept(ORDER)).doesNotThrowAnyException();

		verify(port, times(1)).acceptOrder(ORDER);
	}

	@Test
	@DisplayName("D-346: 상품 하나라도 신규로 남아 있으면 확인된 것으로 보지 않는다")
	void partiallyNew_isNotAccepted() {
		when(port.fetchOrderDetail(ORDER)).thenReturn(detail("N20", "N10"), detail("N20", "N20"));

		assertThatCode(() -> acceptance().accept(ORDER)).doesNotThrowAnyException();

		verify(port, times(1)).acceptOrder(ORDER);
	}

	@Test
	@DisplayName("D-346: 취소요청 등 클레임 상품은 판정에서 빼고 나머지가 배송준비중이면 성공한다")
	void claimItemsAreIgnored() {
		when(port.fetchOrderDetail(ORDER)).thenReturn(detail("N10", "C00"), detail("N20", "C00"));

		assertThatCode(() -> acceptance().accept(ORDER)).doesNotThrowAnyException();

		verify(port, times(1)).acceptOrder(ORDER);
		verify(port, times(2)).fetchOrderDetail(ORDER);
	}

	@Test
	@DisplayName("D-346: 이미 확인된 상품과 클레임 상품뿐이면 다시 보내지 않는다")
	void acceptedPlusClaim_doesNotResend() {
		when(port.fetchOrderDetail(ORDER)).thenReturn(detail("N20", "C00"));

		assertThatCode(() -> acceptance().accept(ORDER)).doesNotThrowAnyException();

		verify(port, never()).acceptOrder(anyString());
	}
}
