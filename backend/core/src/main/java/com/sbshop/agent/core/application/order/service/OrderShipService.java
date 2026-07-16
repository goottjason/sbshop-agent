package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.SettlementPolicy;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sbshop.agent.core.domain.order.enums.ShippingStatus.SHIPPED;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderShipService {

	private final OrderRepository orderRepository;
	private final MarketCredentialRepository credentialRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketplaceShippingService marketplaceShippingService;

	@Transactional
	public BulkShipResult bulkShipOrders(List<Long> orderIds) {
		int successCount = 0;
		int skippedCount = 0;
		List<Long> failedIds = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		if (orderIds == null) {
			return BulkShipResult.builder()
				.successCount(0).failedCount(0).skippedCount(0)
				.failedIds(failedIds).errors(null)
				.build();
		}

		for (Long orderId : orderIds) {
			Order order = orderRepository.findById(orderId).orElse(null);
			if (order == null) {
				// 존재하지 않는 주문은 요청 자체가 잘못된 것 — 실패로 표면화(F-ORD-30/SP-3).
				failedIds.add(orderId);
				errors.add("주문 " + orderId + ": 주문 없음");
				continue;
			}

			MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
			if (cred == null) {
				log.warn("마켓 유형 {}에 대한 인증 정보가 없습니다.", order.getMarketType());
				failedIds.add(orderId);
				errors.add("주문 " + orderId + ": 마켓 인증정보 없음(" + order.getMarketType() + ")");
				continue;
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

				// 이미 발송(SHIPPED)·배송완료(DELIVERED)·종료(취소/반품/교환) 상태면 재발송하지 않는다(F-ORD-29).
				ShippingStatus status = item.getShippingData() != null
					? item.getShippingData().getShippingStatus() : null;
				if (status == ShippingStatus.SHIPPED || status == ShippingStatus.DELIVERED
					|| status == ShippingStatus.CANCELED || status == ShippingStatus.RETURNED
					|| status == ShippingStatus.EXCHANGED) {
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
						.shippingStatus(SHIPPED)
						.build();
					item.applyShippingData(cmd.toShippingData(item.getShippingData()));
					calculateSettlement(item);
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
				failedIds.add(orderId);
				errors.add("주문 " + orderId + ": " + (firstError != null ? firstError : "발송 실패"));
			} else if (orderShipped) {
				successCount++;
			} else if (!anyProcessable) {
				// 발송할 라인이 하나도 없었던 주문(전부 이미 발송/송장없음) — 정상 스킵으로 집계.
				skippedCount++;
			}
		}

		return BulkShipResult.builder()
			.successCount(successCount)
			.failedCount(failedIds.size())
			.skippedCount(skippedCount)
			.failedIds(failedIds)
			.errors(errors.isEmpty() ? null : errors)
			.build();
	}

	static void calculateSettlement(OrderLineItem item) {
		if (item.getSettlementData() != null && item.getSettlementData().getSettlementAmount() != null) {
			BigDecimal currentSettlement = item.getSettlementData().getSettlementAmount();
			BigDecimal settlementAmount = currentSettlement.multiply(SettlementPolicy.SETTLEMENT_FEE_RATE);
			item.applySettlement(settlementAmount);
		}
	}
}
