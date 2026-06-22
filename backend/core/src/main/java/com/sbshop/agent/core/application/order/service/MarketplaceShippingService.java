package com.sbshop.agent.core.application.order.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceShippingService {

	private final OrderRepository orderRepository;
	private final MarketCredentialRepository credentialRepository;
	private final List<MarketOrderPort> marketOrderPorts;

	public MarketOrderPort getPort(MarketType marketType) {
		return marketOrderPorts.stream()
			.filter(port -> port.getMarketType() == marketType)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"지원하지 않는 마켓: " + marketType));
	}

	/**
	 * 마켓에 송장번호를 전송합니다.
	 * - 송장 미전송 상태: 최초 송장 등록 (shipOrder)
	 * - 송장 전송 완료 상태: 송장정보 수정 (updateTracking)
	 * - 취소/반품/교환 상태: 전송 불가
	 */
	public void sendTrackingToMarketplace(OrderLineItem lineItem) {
		Order order = orderRepository.findById(lineItem.getOrderId()).orElse(null);
		if (order == null)
			return;

		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
		if (cred == null)
			return;

		ShippingStatus currentStatus = lineItem.getShippingData() != null
			? lineItem.getShippingData().getShippingStatus() : null;

		// 취소/반품/교환 상태에서는 마켓에 송장 전송 불가
		if (currentStatus == ShippingStatus.CANCELED
			|| currentStatus == ShippingStatus.RETURNED
			|| currentStatus == ShippingStatus.EXCHANGED) {
			log.warn("마켓 배송 전송 불가: 주문 {} 상태가 {}입니다.", order.getMarketOrderNo(), currentStatus);
			return;
		}

		Boolean alreadySent = lineItem.getShippingData() != null
			? lineItem.getShippingData().getTrackingSentToMarket() : null;

		MarketOrderPort port = getPort(order.getMarketType());

		// 이미 마켓에 송장이 전송된 상태 → 송장정보 수정 (updateTracking)
		// 송장 미전송 상태 → 최초 송장 등록 (shipOrder)
		if (Boolean.TRUE.equals(alreadySent)) {
			port.updateTracking(cred, order, lineItem,
				lineItem.getShippingData().getTrackingNo(),
				lineItem.getShippingData().getShippingCarrier());
		} else {
			port.shipOrder(cred, order, lineItem,
				lineItem.getShippingData().getTrackingNo(),
				lineItem.getShippingData().getShippingCarrier());
		}

		log.info("마켓 배송 전송 완료: order={}, market={}", order.getMarketOrderNo(), order.getMarketType());
	}

}
