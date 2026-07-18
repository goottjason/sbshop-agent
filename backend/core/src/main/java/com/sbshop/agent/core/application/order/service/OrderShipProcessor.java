package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.OrderShipOutcome;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-ORD-31: 주문 1건의 발송(외부 마켓 전송 + DB 저장)을 <b>주문 단위의 독립 트랜잭션</b>으로 커밋한다.
 *
 * <p>일괄 발송({@link OrderShipService#bulkShipOrders(List)})이 메서드 전체를 하나의 {@code @Transactional}로
 * 감싸던 종전 구조에서는, 루프 중 한 주문의 마켓 발송(외부 부수효과)이 실제로 일어난 뒤 이후 주문 처리에서
 * 저장 실패·커밋 실패로 트랜잭션이 롤백되면 <b>이미 마켓에 발송된 주문의 DB 상태(SHIPPED 저장)까지 함께
 * 롤백</b>되어 마켓/DB 정합이 깨졌다. 이 빈으로 주문 1건 처리를 분리해 각 주문이 독립 커밋되므로, 한 주문의
 * 실패가 다른 주문의 이미 커밋된 발송을 되돌리지 않는다(F-SYNC-19/20 배치 분리 패턴 재사용).
 *
 * <p>별도 빈으로 분리한 이유: 같은 빈 내부에서 {@code @Transactional} 메서드를 호출하면 self-invocation이라
 * 스프링 AOP 프록시가 적용되지 않아 트랜잭션 경계가 생기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderShipProcessor {

	private final OrderRepository orderRepository;
	private final MarketCredentialRepository credentialRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketplaceShippingService marketplaceShippingService;

	/**
	 * 주문 1건의 발송을 처리하고 결과를 반환한다. 이 메서드 호출 하나가 독립된 트랜잭션이며,
	 * 정상 반환 시 해당 주문의 발송 상태 저장이 커밋된다. 실패는 예외로 전파하지 않고
	 * {@link OrderShipOutcome#failed(String)}로 반환하여 다른 주문 처리·이미 커밋된 발송에 영향이 없게 한다.
	 */
	@Transactional
	public OrderShipOutcome shipSingleOrder(Long orderId) {
		Order order = orderRepository.findById(orderId).orElse(null);
		if (order == null) {
			// 존재하지 않는 주문은 요청 자체가 잘못된 것 — 실패로 표면화(F-ORD-30/SP-3).
			return OrderShipOutcome.failed("주문 " + orderId + ": 주문 없음");
		}

		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
		if (cred == null) {
			log.warn("마켓 유형 {}에 대한 인증 정보가 없습니다.", order.getMarketType());
			return OrderShipOutcome.failed("주문 " + orderId + ": 마켓 인증정보 없음(" + order.getMarketType() + ")");
		}

		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(orderId);
		// 주문 단위 집계: 하나라도 발송 성공하면 shipped, 하나라도 실패하면 그 주문을 failed로 본다.
		boolean orderShipped = false;
		boolean orderFailed = false;
		boolean anyProcessable = false;
		String firstError = null;

		for (OrderLineItem item : lineItems) {
			String trackingNo = item.getShippingData() != null ? item.getShippingData().getTrackingNo() : null;
			if (trackingNo == null || trackingNo.isEmpty()) {
				// 송장 없는 라인은 정상 스킵(발송 대상 아님).
				continue;
			}

			// 이미 배송지시(DISPATCHED)·발송(SHIPPED)·배송완료(DELIVERED)·종료(취소/반품/교환) 상태면 재발송하지 않는다(F-ORD-29).
			ShippingStatus status = item.getShippingData() != null
				? item.getShippingData().getShippingStatus() : null;
			if (status == ShippingStatus.DISPATCHED || status == ShippingStatus.SHIPPED
				|| status == ShippingStatus.DELIVERED || status == ShippingStatus.CANCELED
				|| status == ShippingStatus.RETURNED || status == ShippingStatus.EXCHANGED) {
				log.info("라인아이템 {} 스킵 — 이미 {} 상태(재발송 대상 아님)", item.getId(), status);
				continue;
			}

			anyProcessable = true;
			ShippingCarrier carrier = item.getShippingData() != null
				? item.getShippingData().getShippingCarrier() : null;

			try {
				marketplaceShippingService.getPort(order.getMarketType())
					.shipOrder(cred, order, item, trackingNo, carrier);

				ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
					.trackingNo(trackingNo)
					.shippingStatus(ShippingStatus.DISPATCHED)
					.build();
				item.applyShippingData(cmd.toShippingData(item.getShippingData()));
				// 정산액은 주문 수집(sync) 시점에 마켓별 요율로 1회 계산·저장된다.
				// 종전엔 여기서 다시 ×0.89를 곱해 이중 차감되던 버그가 있어 제거했다(F-SYNC-4/F-ORD-32 후속).
				orderLineItemRepository.save(item);
				orderShipped = true;
			} catch (Exception e) {
				// 마켓 shipOrder 실패를 삼키지 않고 표면화한다(F-ORD-30). 로그만 남기던 종전 동작을 교체.
				log.error("라인아이템 {} 배송 처리 실패: {}", item.getId(), e.getMessage());
				orderFailed = true;
				if (firstError == null) {
					firstError = e.getMessage();
				}
			}
		}

		if (orderFailed) {
			return OrderShipOutcome.failed(
				"주문 " + orderId + ": " + (firstError != null ? firstError : "발송 실패"));
		} else if (orderShipped) {
			return OrderShipOutcome.shipped();
		} else if (!anyProcessable) {
			// 발송할 라인이 하나도 없었던 주문(전부 이미 발송/송장없음) — 정상 스킵으로 집계.
			return OrderShipOutcome.skipped();
		}
		// anyProcessable 였으나 orderShipped/orderFailed 모두 false인 경우는 없다(발송 시도=성공 또는 실패).
		return OrderShipOutcome.skipped();
	}
}
