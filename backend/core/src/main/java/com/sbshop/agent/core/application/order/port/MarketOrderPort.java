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
	 * 상품 배송 시작
	 */
	void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier);

	/**
	 * 주문 접수/확인
	 */
	void acceptOrders(MarketCredential credential, Order order);

	/**
	 * 정산 조회 (선택적 구현, 미구현 시 빈 맵 반환)
	 */
	default Map<String, BigDecimal> querySettlement(MarketCredential credential,
		LocalDate from, LocalDate to) {
		return Collections.emptyMap();
	}

	/**
	 * 배송사 코드 변환 (마켓별 다를 수 있음)
	 */
	default String mapCarrierCode(ShippingCarrier carrier) {
		if (carrier == null)
			return "CJGLS";
		return switch (carrier) {
			case CJ_LOGISTICS -> "CJGLS";
			case HANJIN -> "HANJIN";
			case KOREA_POST -> "EPOST";
			case LOTTE_LOGISTICS -> "LOTTE";
			case ROCKET -> "COUPANG";
			default -> "CJGLS";
		};
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
