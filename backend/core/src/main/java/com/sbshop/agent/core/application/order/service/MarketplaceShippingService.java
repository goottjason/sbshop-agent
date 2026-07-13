package com.sbshop.agent.core.application.order.service;

import java.util.List;
import java.util.Optional;

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

		return findPort(marketType)
			.orElseThrow(() -> new IllegalArgumentException(
				"지원하지 않는 마켓: " + marketType));
	}

	/** 배송 어댑터가 있는 마켓만 Optional로 반환(미지원 마켓은 empty). */
	public Optional<MarketOrderPort> findPort(MarketType marketType) {
		return marketOrderPorts.stream()
			.filter(port -> port.getMarketType() == marketType)
			.findFirst();
	}

	/**
	 * 마켓에 송장번호 전송
	 * - 마켓에 송장이 아직 없으면(최초) 등록 (shipOrder)
	 * - 마켓에 송장이 이미 존재하면 수정 (updateTracking)
	 * - 취소/반품/교환 상태면 전송 불가
	 *
	 * <p>초기등록/수정 판단은 {@code invoiceAlreadyExists}(이번 편집 이전에 이미 마켓에 송장이
	 * 존재했는지)로 결정한다. 과거에는 {@code trackingSentToMarket}(우리 시스템이 전송한 적 있는지)로
	 * 판단했으나, 판매자/마켓이 마켓에서 직접 송장을 등록·수정한 뒤 동기화로 우리 DB에 유입된 경우
	 * 이 플래그가 false로 남아 이미 배송진행된 주문에 초기등록 API를 호출해 쿠팡이 거부하는 결함이
	 * 있었다(“배송진행상태가 유효하지 않습니다”). 이 판단은 호출자가 편집 이전 상태로 계산해 넘긴다.
	 *
	 * D-069: 마켓 API 실패를 예외로 밖에 던지지 않고 {@link MarketShippingResult}로 표면화한다.
	 * 호출자의 @Transactional 배송정보 저장이 마켓 전송 실패로 롤백되지 않도록 하기 위함이며,
	 * 실패(isFailed)인 경우 호출자는 trackingSentToMarket을 마킹하지 말아야 재시도가 가능하다.
	 *
	 * @param invoiceAlreadyExists 이번 편집 이전에 마켓에 송장이 이미 존재했는지 — true면 수정, false면 최초 등록
	 */
	public MarketShippingResult sendTrackingToMarketplace(OrderLineItem lineItem, boolean invoiceAlreadyExists) {

		// 주문 조회
		Order order = orderRepository.findById(lineItem.getOrderId()).orElse(null);
		if (order == null) {
			log.warn("마켓 배송 전송 스킵: 주문 없음 orderId={}", lineItem.getOrderId());
			return MarketShippingResult.ofSkipped("주문 없음");
		}

		// 마켓크레덴셜 조회(nullable). Cafe24 기반 배송(G마켓/옥션)은 마켓 자격증명이 아니라
		// Cafe24 토큰을 쓰므로, cred가 없어도(옥션 등) 조기 종료하지 않고 포트에 위임한다.
		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);

		// 현재 배송상태 확인
		ShippingStatus currentStatus = lineItem.getShippingData() != null
			? lineItem.getShippingData().getShippingStatus() : null;

		// 취소/반품/교환 상태이면 전송 불가
		if (currentStatus == ShippingStatus.CANCELED
			|| currentStatus == ShippingStatus.RETURNED
			|| currentStatus == ShippingStatus.EXCHANGED) {
			log.warn("마켓 배송 전송 불가: 주문 {} 상태가 {}입니다.", order.getMarketOrderNo(), currentStatus);
			return MarketShippingResult.ofSkipped("전송 불가 상태: " + currentStatus);
		}

		// 마켓 포트 조회 — 배송 어댑터가 없는 마켓(카페24 등)은 크래시 대신 스킵(배송정보 수정 자체는 성공 유지).
		Optional<MarketOrderPort> portOpt = findPort(order.getMarketType());
		if (portOpt.isEmpty()) {
			log.warn("[배송전파] {} 마켓은 배송 어댑터 미지원 — 마켓 전송 스킵(자사 배송정보는 저장됨): order={}",
				order.getMarketType(), order.getMarketOrderNo());
			return MarketShippingResult.ofSkipped("배송 어댑터 미지원: " + order.getMarketType());
		}
		MarketOrderPort port = portOpt.get();

		// 전송 또는 수정 처리 — 마켓 API 예외는 삼키지 않고 실패 결과로 반환(롤백 유발 방지, 재시도 보존).
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
			return MarketShippingResult.ofFailed(e.getMessage());
		}

		log.info("마켓 배송 전송 완료: order={}, market={}", order.getMarketOrderNo(), order.getMarketType());
		return MarketShippingResult.ofSent();
	}

	/** 마켓에 주문 취소 요청. Cafe24 기반(G마켓/옥션)은 마켓 자격증명이 아니라 Cafe24 토큰으로 인증하므로
	 *  cred가 없어도 포트에 위임한다(송장 역전송 경로와 동일 규율). 포트 호출 실패는 상위로 전파한다. */
	public void cancelOrderToMarketplace(Order order) {

		// 마켓크레덴셜 조회(nullable). Cafe24 기반 취소(G마켓/옥션)는 마켓 자격증명이 아니라
		// Cafe24 토큰을 쓰므로, cred가 없어도 조기 종료하지 않고 포트에 위임한다.
		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
		MarketOrderPort port = getPort(order.getMarketType());
		port.cancelOrder(cred, order);

		log.info("마켓 주문취소 완료: order={}, market={}", order.getMarketOrderNo(), order.getMarketType());
	}

}
