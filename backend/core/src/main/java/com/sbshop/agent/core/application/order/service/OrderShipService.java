package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.application.order.dto.OrderShipOutcome;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 일괄 발송 오케스트레이션(F-ORD-30 / F-ORD-31).
 *
 * <p>F-ORD-31: 이 메서드는 <b>@Transactional이 아니다</b>. 여러 주문의 발송을 하나의 트랜잭션으로 묶으면,
 * 루프 중 한 주문이 마켓에 실제 발송(외부 부수효과)된 뒤 이후 주문에서 실패해 전체가 롤백되면 마켓엔
 * 발송됐으나 DB엔 미반영되는 정합 붕괴가 생긴다. 실제 발송(외부 전송 + DB 저장)은 주문 단위 독립
 * 트랜잭션({@link OrderShipProcessor#shipSingleOrder(Long)})으로 분리하고, 이 루프는 그 결과만
 * {@link BulkShipResult}로 집계한다. 한 주문의 실패/롤백이 다른 주문의 이미 커밋된 발송을 되돌리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderShipService {

	private final OrderShipProcessor orderShipProcessor;

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
			// 주문 1건은 독립 트랜잭션으로 커밋된다 — 여기서 실패를 예외로 받지 않고 결과로 집계하므로
			// 한 주문의 실패가 다른 주문의 이미 커밋된 발송을 되돌리지 않는다(F-ORD-31).
			OrderShipOutcome outcome = orderShipProcessor.shipSingleOrder(orderId);
			if (outcome.isFailed()) {
				failedIds.add(orderId);
				errors.add(outcome.getErrorMessage());
			} else if (outcome.isShipped()) {
				successCount++;
			} else {
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

}
