package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * D-069 계약 반전: 마켓 송장 반영이 실패(isFailed)하면 updateShippingInfo가 예외를 던져
 * @Transactional 롤백으로 DB/마켓 정합을 유지한다. 스킵은 로컬 편집 보존, 성공은 전송완료 마킹.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceShippingRollbackTest {

	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private MarketplaceShippingService marketplaceShippingService;

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService);
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
	@DisplayName("SHIPPED 송장수정 → 마켓 반영 실패(ofFailed) → 예외 발생(롤백 계약), 메시지에 사유 포함")
	void marketFailed_throwsToRollback() {
		OrderLineItem item = shippedItem();
		when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
		lenient().when(orderRepository.findById(10L))
			.thenReturn(Optional.of(Order.builder().marketType(MarketType.COUPANG).build()));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofFailed("쿠팡 거부: 유효하지 않은 송장번호"));

		assertThatThrownBy(() -> service().updateShippingInfo(1L, command()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("쿠팡 거부: 유효하지 않은 송장번호");

		// 실패 시 전송완료 마킹(markTrackingAsSent 저장)은 발생하지 않는다 — 롤백으로 처리.
		assertThat(item.getShippingData().getTrackingSentToMarket()).isNotEqualTo(Boolean.TRUE);
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
