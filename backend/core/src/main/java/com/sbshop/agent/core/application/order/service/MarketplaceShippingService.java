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
			// 마켓 상태 잠금(배송중/배송완료 등)으로 인한 영구 거부는 재시도해도 성공 불가 → 종결(D-E6).
			// D-150: 어댑터·클라이언트가 예외를 래핑하면 최상위 메시지에서 사유가 사라진다. 사유를 놓치면
			// 영구 거부가 일시 실패로 분류돼 30분마다 같은 거부를 받아내고, 수동수정 표시도 서지 않는다.
			if (isNonRetryableMarketState(e.getMessage())
				|| isNonRetryableMarketState(RootCauseExtractor.rootMessage(e))) {
				return MarketShippingResult.ofTerminal(e.getMessage());
			}
			return MarketShippingResult.ofFailed(e.getMessage());
		}

		log.info("마켓 배송 전송 완료: order={}, market={}", order.getMarketOrderNo(), order.getMarketType());
		return MarketShippingResult.ofSent();
	}

	/**
	 * 재시도 불가한 마켓 상태 잠금 오류인지 판별한다(D-E6, D-123).
	 *
	 * 마켓은 주문이 일정 상태를 넘어가면 송장 등록·수정을 영구 거부한다. 이때 미마킹으로 두면
	 * 30분마다 같은 요청이 영원히 재전송된다 — 성공할 수 없는 호출이므로 종결시켜야 한다.
	 *
	 * D-123: 종전에는 쿠팡 문구만 알고 있어 11번가·Cafe24의 영구 거부가 "일시 실패"로 분류됐고,
	 * 실제로 여러 주문이 매 사이클 재시도를 반복하고 있었다. 마켓별 문구를 함께 인식한다.
	 * 판정은 문구 기반이므로 마켓이 메시지를 바꾸면 다시 무한 재시도로 돌아간다 — 재시도 반복이
	 * 관측되면 이 목록부터 확인할 것.
	 */
	private boolean isNonRetryableMarketState(String message) {
		if (message == null) {
			return false;
		}
		// 쿠팡: 배송중/배송완료로 넘어가면 송장 업로드·수정 거부
		boolean coupangLocked = message.contains("배송진행상태가 유효하지 않습니다")
			|| message.contains("이미 배송완료")
			|| message.contains("배송완료된");

		// Cafe24(G마켓·옥션): 이미 shipping 등으로 넘어간 주문에 배송 등록 시 422
		// D-154: 동사를 바꾸면 거부 문구도 바뀐다. D-151로 POST→PUT이 되자 Cafe24는 다른 사유를 돌려줬다 —
		// "cannot be edited for marketplace orders"(마켓 연동 주문은 송장 수정 자체가 불가).
		// 그 문구가 목록에 없어 영구 거부가 재시도 대상으로 샜다. 두 문구를 함께 인식한다.
		// 사람의 조치 경로는 Cafe24 관리자가 아니라 G마켓·옥션 판매자센터(ESM+)다.
		boolean cafe24StateLocked = message.contains("You cannot change to that order state")
			|| message.contains("cannot be edited for marketplace orders");

		// D-145: 네이버는 배송중 주문의 송장 수정을 영구 거부한다 — 수정 API 자체가 없다(커머스API 공식
		// 답변 2건 + 2026-08-07 라이브 시험: 올바른 택배사 코드로 재호출해도 같은 9999, 마켓 값 불변).
		// 재시도로는 절대 성공하지 못하므로 종결시키고 사람의 수동 수정(스토어센터)으로 넘긴다.
		// 택배사 코드 오류(104119)는 여기 넣지 않는다 — 그건 우리 요청 오류라 고치면 재시도로 성공한다(D-128).
		boolean smartStoreStateLocked = message.contains("주문상태 및 클레임상태를 확인하세요");

		// D-146: 11번가도 발송된 주문의 송장 수정 API가 없다(2026-08-08 확정). 3중 확증 —
		// ① 게이트웨이가 키 없이 등록 경로를 알려주는 성질(-100 등록 / -997 미등록)로 12개 서비스 그룹 ×
		//    1,150여 경로 패턴을 전수 조회했으나 수정 계열 0건
		// ② 외부 연동 5개 저장소(PHP·Ruby·Python·Laravel)가 쓰는 11번가 경로 25개에도 없음
		// ③ 라이브: 발송처리 재호출은 5·8-파라미터 모두 -3313. reqdelivery에 미지의 9-파라미터 변형이
		//    등록돼 있으나 6번 자리가 "추가 송장번호"(분할발송용)라 대체가 아닌 덧붙이기다.
		// 따라서 재시도로는 절대 성공하지 못한다 → 종결시키고 셀러오피스 수동 수정으로 넘긴다.
		// 상태 값(배송중·구매확정 등)에 의존하지 않도록 앞부분 문구만 본다.
		boolean elevenstStateLocked = message.contains("주문상태가 이미 변경 되었습니다");

		// D-128(D-123 정정): 11번가 "존재하지 않는 배송번호"는 여기서 제외한다.
		// 마켓의 상태 잠금이 아니라 우리 요청이 잘못됐다는 응답이었고(D-127 — 배송번호 자리에 주문번호
		// 전달), 그 오분류가 EmailFetcher의 종결 처리를 타 trackingSentToMarket을 거짓으로 true로
		// 만들어 "마켓 미반영인데 반영됨"인 주문들을 남겼다. 페이로드 교정 후에는 재시도로 성공할 수
		// 있으므로 일시 실패로 둔다. 재시도가 무한 반복되면 원인은 페이로드지 분류가 아니다.
		return coupangLocked || cafe24StateLocked || smartStoreStateLocked || elevenstStateLocked;
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
