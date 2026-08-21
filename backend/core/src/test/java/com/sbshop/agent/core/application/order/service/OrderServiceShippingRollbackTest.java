package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * D-125 계약 재반전: 마켓 송장 반영이 실패해도 로컬 저장은 롤백하지 않는다.
 *
 * D-069 후속으로 "실패 시 롤백"을 택했던 근거는 "마켓 송장이 동기화로 반영된다"였다.
 * ESM+(G마켓·옥션)에서는 Cafe24에 자체배송 자리표시자만 등록되고 마켓 실송장이 유입되지
 * 않음이 확증돼(D-124), 롤백이 정합을 지키는 게 아니라 기록 자체를 막고 있었다.
 * 이제 실패해도 로컬 송장은 보존하고, 미전송은 trackingSentToMarket으로 표현한다.
 * 스킵은 종전대로 로컬 편집 보존, 성공은 전송완료 마킹.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceShippingRollbackTest {

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;

	/**
	 * D-133: 송장 쓰기 통로는 <b>진짜 객체</b>를 끼운다. 목으로 대체하면 라인아이템 쓰기 자체가
	 * 사라져 기존 검증이 통과해도 아무것도 증명하지 못한다. {@code shipment_id}가 null인 이
	 * 테스트들에서는 통로가 배송을 건드리지 않으므로 종전과 동작이 같다 — 그 사실이 회귀 증거다.
	 */
	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private MarketplaceShippingService marketplaceShippingService;

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService, shippingWriter());
	}

	private OrderLineItem shippedItem() {
		return OrderLineItem.builder()
			.orderId(10L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.SHIPPED).build())
			.build();
	}

	private ShippingUpdateCommand command() {
		return ShippingUpdateCommand.builder()
			.trackingNo("123456789")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.build();
	}

	@Test
	@DisplayName("D-125: SHIPPED 송장수정 → 마켓 반영 실패(ofFailed) → 예외 없이 로컬 송장 보존")
	void marketFailed_preservesLocalTracking() {
		OrderLineItem item = shippedItem();
		when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
		lenient().when(orderRepository.findById(10L))
			.thenReturn(Optional.of(Order.builder().marketType(MarketType.COUPANG).build()));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofFailed("쿠팡 거부: 유효하지 않은 송장번호"));

		org.assertj.core.api.Assertions
			.assertThatCode(() -> service().updateShippingInfo(1L, command()))
			.doesNotThrowAnyException();

		assertThat(item.getShippingData().getTrackingNo()).isEqualTo("123456789");
		// 전송은 실패했으므로 마킹하지 않는다 — 다음 사이클 재시도 대상으로 남는다.
		assertThat(item.getShippingData().getTrackingSentToMarket()).isNotEqualTo(Boolean.TRUE);
		verify(orderLineItemRepository).save(same(item));
	}

	@Test
	@DisplayName("SHIPPED 송장수정 → 마켓 반영 성공(ofSent) → 예외 없음, 갱신 아이템 반환")
	void marketSent_noThrow_returnsUpdatedItem() {
		OrderLineItem item = shippedItem();
		when(orderLineItemRepository.findById(2L)).thenReturn(Optional.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofSent());

		OrderLineItem result = service().updateShippingInfo(2L, command());

		assertThat(result).isSameAs(item);
		assertThat(result.getShippingData().getTrackingNo()).isEqualTo("123456789");
		assertThat(result.getShippingData().getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("SHIPPED 송장수정 → 마켓 스킵(ofSkipped) → 예외 없음(로컬 편집 보존)")
	void marketSkipped_noThrow_localKept() {
		OrderLineItem item = shippedItem();
		when(orderLineItemRepository.findById(3L)).thenReturn(Optional.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofSkipped("배송 어댑터 미지원"));

		OrderLineItem result = service().updateShippingInfo(3L, command());

		assertThat(result.getShippingData().getTrackingNo()).isEqualTo("123456789");
		// 스킵은 전송완료로 마킹하지 않는다.
		assertThat(result.getShippingData().getTrackingSentToMarket()).isNotEqualTo(Boolean.TRUE);
	}

	@Test
	@DisplayName("SHIPPED + 기존 송장 존재 → sendTrackingToMarketplace(item, true) 호출(수정)")
	void updateShipping_shippedWithExistingInvoice_passesTrue() {
		// 이미 송장(TRK-OLD)이 있는 SHIPPED 아이템 — 동기화 유입분 포함. trackingSentToMarket은 false여도 무관.
		OrderLineItem item = OrderLineItem.builder()
			.orderId(10L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.SHIPPED)
				.trackingNo("TRK-OLD")
				.build())
			.build();
		when(orderLineItemRepository.findById(4L)).thenReturn(Optional.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), eq(true)))
			.thenReturn(MarketShippingResult.ofSent());

		service().updateShippingInfo(4L, command());

		verify(marketplaceShippingService).sendTrackingToMarketplace(same(item), eq(true));
	}

	@Test
	@DisplayName("PREPARING + 기존 송장 없음 → sendTrackingToMarketplace(item, false) 호출(최초 등록)")
	void updateShipping_preparingWithoutInvoice_passesFalse() {
		// 송장이 없는 PREPARING 아이템 — 진짜 최초 배송처리(DISPATCHED 전이).
		OrderLineItem item = OrderLineItem.builder()
			.orderId(10L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.PREPARING)
				.build())
			.build();
		when(orderLineItemRepository.findById(5L)).thenReturn(Optional.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), eq(false)))
			.thenReturn(MarketShippingResult.ofSent());

		service().updateShippingInfo(5L, command());

		verify(marketplaceShippingService).sendTrackingToMarketplace(same(item), eq(false));
	}
}
