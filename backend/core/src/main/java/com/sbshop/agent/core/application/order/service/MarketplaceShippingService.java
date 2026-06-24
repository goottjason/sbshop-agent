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

	/** 마켓 타입에 맞는 포트 조회 */
	public MarketOrderPort getPort(MarketType marketType) {

		return marketOrderPorts.stream()
			.filter(port -> port.getMarketType() == marketType)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"지원하지 않는 마켓: " + marketType));
	}

	/**
	 * 마켓에 송장번호 전송
	 * - 미전송 상태면 최초 등록 (shipOrder)
	 * - 전송 완료 상태면 수정 (updateTracking)
	 * - 취소/반품/교환 상태면 전송 불가
	 */
	public void sendTrackingToMarketplace(OrderLineItem lineItem) {

		// 주문 조회
		Order order = orderRepository.findById(lineItem.getOrderId()).orElse(null);
		if (order == null)
			return;

		// 마켓크레덴셜 조회
		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
		if (cred == null)
			return;

		// 현재 배송상태 확인
		ShippingStatus currentStatus = lineItem.getShippingData() != null
			? lineItem.getShippingData().getShippingStatus() : null;

		// 취소/반품/교환 상태이면 전송 불가
		if (currentStatus == ShippingStatus.CANCELED
			|| currentStatus == ShippingStatus.RETURNED
			|| currentStatus == ShippingStatus.EXCHANGED) {
			log.warn("마켓 배송 전송 불가: 주문 {} 상태가 {}입니다.", order.getMarketOrderNo(), currentStatus);
			return;
		}

		// 송장 전송 여부 확인
		Boolean alreadySent = lineItem.getShippingData() != null
			? lineItem.getShippingData().getTrackingSentToMarket() : null;

		// 마켓 포트 조회
		MarketOrderPort port = getPort(order.getMarketType());

		// 전송 또는 수정 처리
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

	/** 마켓에 주문 취소 요청 */
	public void cancelOrderToMarketplace(Order order) {

		// 마켓크레덴셜 조회
		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
		if (cred == null) {
			log.warn("마켓 인증 정보 없음: market={}", order.getMarketType());
			return;
		}

		// 마켓에 취소 요청
		MarketOrderPort port = getPort(order.getMarketType());
		port.cancelOrder(cred, order);

		log.info("마켓 주문취소 완료: order={}, market={}", order.getMarketOrderNo(), order.getMarketType());
	}

}
