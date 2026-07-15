package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.CustomsVerificationResult;
import com.sbshop.agent.core.application.order.port.CustomsClearancePort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-SYNC-19 / F-SYNC-20: 통관 배치 처리를 별도 빈으로 분리하여 <b>배치 단위의 짧은 트랜잭션</b>으로 커밋한다.
 *
 * <p>{@link CustomsOrderSyncService}의 오케스트레이션 루프는 트랜잭션이 없으며(그래야 배치 사이의
 * {@code Thread.sleep()}이 트랜잭션을 물지 않는다), 실제 GSI Express 검증·상태 갱신·저장만 이 빈의
 * {@code @Transactional} 메서드에서 수행된다.
 *
 * <p>별도 빈으로 분리한 이유: 같은 빈 내부에서 {@code @Transactional} 메서드를 호출하면
 * self-invocation이라 스프링 AOP 프록시가 적용되지 않아 트랜잭션 경계가 생기지 않는다.
 * 배치마다 독립 커밋되므로 중간 배치에서 실패해도 이전 배치의 진행은 보존된다(부분 진행 보존, F-SYNC-20).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomsBatchProcessor {

	private final OrderRepository orderRepository;
	private final CustomsClearancePort customsClearancePort;

	/**
	 * 한 배치(최대 30건)의 통관 상태를 GSI Express로 검증하고 결과를 반영한 뒤 명시적으로 저장한다.
	 * 이 메서드 호출 하나가 독립된 트랜잭션이며, 정상 반환 시 배치가 커밋된다.
	 */
	@Transactional
	public void processBatch(List<Order> batch) {
		Map<Long, CustomsVerificationResult> resultMap = customsClearancePort.verifyBulk(batch);
		for (Order order : batch) {
			CustomsVerificationResult result = resultMap.getOrDefault(order.getId(),
				CustomsVerificationResult.pending());
			order.updateCustomsStatus(result.getStatus(), result.getVerifiedPerson());
		}
		// 오케스트레이션 트랜잭션이 없으므로 dirty checking에 의존하지 않고 명시적으로 저장한다.
		orderRepository.saveAll(batch);
	}
}
