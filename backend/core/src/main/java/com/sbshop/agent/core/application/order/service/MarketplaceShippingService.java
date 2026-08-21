package com.sbshop.agent.core.application.order.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.common.RootCauseExtractor;
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
		return findPort(marketType)
			.orElseThrow(() -> new IllegalArgumentException(
				"지원하지 않는 마켓: " + marketType));
	}

	public Optional<MarketOrderPort> findPort(MarketType marketType) {
		return marketOrderPorts.stream()
			.filter(port -> port.getMarketType() == marketType)
			.findFirst();
	}

	public MarketShippingResult sendTrackingToMarketplace(OrderLineItem lineItem, boolean invoiceAlreadyExists) {
		Order order = orderRepository.findById(lineItem.getOrderId()).orElse(null);
		if (order == null) {
			log.warn("마켓 배송 전송 스킵: 주문 없음 orderId={}", lineItem.getOrderId());
			return MarketShippingResult.ofSkipped("주문 없음");
		}

		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);

		ShippingStatus currentStatus = lineItem.getShippingData() != null
			? lineItem.getShippingData().getShippingStatus() : null;

		if (currentStatus == ShippingStatus.CANCELED
			|| currentStatus == ShippingStatus.RETURNED
			|| currentStatus == ShippingStatus.EXCHANGED) {
			log.warn("마켓 배송 전송 불가: 주문 {} 상태가 {}입니다.", order.getMarketOrderNo(), currentStatus);
			return MarketShippingResult.ofSkipped("전송 불가 상태: " + currentStatus);
		}

		Optional<MarketOrderPort> portOpt = findPort(order.getMarketType());
		if (portOpt.isEmpty()) {
			log.warn("[배송전파] {} 마켓은 배송 어댑터 미지원 — 마켓 전송 스킵(자사 배송정보는 저장됨): order={}",
				order.getMarketType(), order.getMarketOrderNo());
			return MarketShippingResult.ofSkipped("배송 어댑터 미지원: " + order.getMarketType());
		}
		MarketOrderPort port = portOpt.get();

		try {
			if (invoiceAlreadyExists) {
				port.updateTracking(cred, order, lineItem,
					lineItem.getShippingData().getTrackingNo(),
					lineItem.getShippingData().getShippingCarrier());
			} else {
				port.shipOrder(cred, order, lineItem,
					lineItem.getShippingData().getTrackingNo(),
					lineItem.getShippingData().getShippingCarrier());
			}
		} catch (RuntimeException e) {
			log.error("마켓 배송 전송 실패: order={}, market={}, reason={}",
				order.getMarketOrderNo(), order.getMarketType(), e.getMessage(), e);
			if (isNonRetryableMarketState(e.getMessage())
				|| isNonRetryableMarketState(RootCauseExtractor.rootMessage(e))) {
				return MarketShippingResult.ofTerminal(e.getMessage());
			}
			return MarketShippingResult.ofFailed(e.getMessage());
		}

		log.info("마켓 배송 전송 완료: order={}, market={}", order.getMarketOrderNo(), order.getMarketType());
		return MarketShippingResult.ofSent();
	}

	public void cancelOrderToMarketplace(Order order) {
		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
		MarketOrderPort port = getPort(order.getMarketType());
		port.cancelOrder(cred, order);

		log.info("마켓 주문취소 완료: order={}, market={}", order.getMarketOrderNo(), order.getMarketType());
	}

	private boolean isNonRetryableMarketState(String message) {
		if (message == null) {
			return false;
		}
		boolean coupangLocked = message.contains("배송진행상태가 유효하지 않습니다")
			|| message.contains("이미 배송완료")
			|| message.contains("배송완료된");

		boolean cafe24StateLocked = message.contains("You cannot change to that order state")
			|| message.contains("cannot be edited for marketplace orders");

		boolean smartStoreStateLocked = message.contains("주문상태 및 클레임상태를 확인하세요");

		boolean elevenstStateLocked = message.contains("주문상태가 이미 변경 되었습니다");

		return coupangLocked || cafe24StateLocked || smartStoreStateLocked || elevenstStateLocked;
	}
}
