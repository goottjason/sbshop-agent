package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * DTO 기반 주문 sync 서비스(쿠팡·스마트스토어·11번가)가 공유하는 <b>주문 upsert 분기 골격</b>만
 * 추출한 상태 없는 헬퍼(F-SYNC-5). 주문 목록을 순회하며 marketOrderNo로 기존/신규를 판정하고,
 * 실제 갱신/생성은 각 서비스가 넘긴 콜백에 위임한다.
 *
 * <p><b>추출 범위는 이 분기 루프뿐이다.</b> 마켓별 파싱·필드 매핑·상태 판정·취소 감지·정산액 계산·
 * trackingSentToMarket 보존 가드 같은 고유 로직은 각 서비스의 update/create 콜백 안에 그대로 남는다
 * (동작 불변). 로그 태그만 마켓별로 주입해 기존 로그 포맷을 그대로 보존한다.
 */
@Slf4j
final class MarketOrderUpsertDispatcher {

	private MarketOrderUpsertDispatcher() {}

	/**
	 * @param orders          어댑터가 반환한 마켓 주문 DTO 목록
	 * @param orderRepository marketOrderNo로 기존 주문을 조회할 리포지토리
	 * @param logTag          로그 접두 태그(예: "COUPANG", "SMART_STORE", "ELEVEN_STREET")
	 * @param onExisting      기존 주문 발견 시 (기존 Order, dto)로 호출되는 갱신 콜백
	 * @param onNew           신규 주문일 때 dto로 호출되는 생성 콜백
	 */
	static void dispatch(
		List<MarketOrderDto> orders,
		OrderRepository orderRepository,
		String logTag,
		BiConsumer<Order, MarketOrderDto> onExisting,
		Consumer<MarketOrderDto> onNew) {
		for (MarketOrderDto dto : orders) {
			log.info("[{}] 처리 중: orderNo={}, status={}", logTag, dto.getMarketOrderNo(), dto.getStatus());
			Optional<Order> existingOrder = orderRepository.findByMarketOrderNo(dto.getMarketOrderNo());

			if (existingOrder.isPresent()) {
				log.info("[{}] 기존 주문 발견: id={}, orderNo={}",
					logTag, existingOrder.get().getId(), dto.getMarketOrderNo());
				onExisting.accept(existingOrder.get(), dto);
			} else {
				log.info("[{}] 신규 주문 생성 시도: orderNo={}", logTag, dto.getMarketOrderNo());
				onNew.accept(dto);
			}
		}
	}
}
