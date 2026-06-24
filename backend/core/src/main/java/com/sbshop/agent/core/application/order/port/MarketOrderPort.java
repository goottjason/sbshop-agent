package com.sbshop.agent.core.application.order.port;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 마켓별 주문 API를 통합 인터페이스로 추상화한 포트
 * 각 마켓 어댑터가 이 인터페이스를 구현하여 동기화 로직을 처리
 */
public interface MarketOrderPort {

	/**
	 * 마켓 타입 식별
	 */
	MarketType getMarketType();

	/**
	 * 주문 조회 (내부에서 페이징/상태별 분리 로직 처리)
	 */
	List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate);

	/**
	 * 상품 배송 시작 (송장 업로드)
	 */
	void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier);

	/**
	 * 송장 정보 수정 (송장 업데이트)
	 * 기본 구현은 shipOrder와 동일하게 동작 (update API가 없는 마켓용)
	 */
	default void updateTracking(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		shipOrder(credential, order, lineItem, trackingNo, carrier);
	}

	/**
	 * 주문 접수/확인
	 */
	void acceptOrders(MarketCredential credential, Order order);

	/**
	 * 주문 취소
	 * 결제완료/상품준비중 상태의 주문을 취소합니다.
	 * 미구현 시 UnsupportedOperationException 발생
	 */
	default void cancelOrder(MarketCredential credential, Order order) {
		throw new UnsupportedOperationException("이 마켓은 주문 취소를 지원하지 않습니다.");
	}

	/**
	 * 정산 조회 (선택적 구현, 미구현 시 빈 맵 반환)
	 */
	default Map<String, BigDecimal> querySettlement(MarketCredential credential,
		LocalDate from, LocalDate to) {
		return Collections.emptyMap();
	}

	/**
	 * 개별 주문 상세 조회 (폴백용)
	 * 최소 데이터로 생성된 주문의 전체 정보를 가져올 때 사용
	 *
	 * @param credential 마켓 크레덴셜
	 * @param dto 최소 데이터 주문 DTO (marketSpecificData 포함)
	 * @return 전체 데이터가 포함된 DTO, 실패 시 null
	 */
	default MarketOrderDto fetchOrderDetail(MarketCredential credential, MarketOrderDto dto) {
		return null;
	}
}
