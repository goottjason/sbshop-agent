package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-PSRC-14: 마켓 게시 등록행의 DB 쓰기를 되돌릴 수 없는 외부 게시 호출과 분리하기 위한 트랜잭션 경계.
 * <p>
 * {@link ProductPublishUseCase}는 이 두 메서드를 외부 {@code client.publish()} 호출 앞뒤로 나눠 부르며,
 * 각 DB 쓰기가 짧은 독립 트랜잭션({@link Propagation#REQUIRES_NEW})으로 커밋되게 한다. 이렇게 하면
 * <ul>
 *   <li>PENDING 행이 외부 게시 <em>전에</em> 커밋돼 상태를 확보하고(게시가 성공하면 반드시 우리 DB에 흔적이 남는다),</li>
 *   <li>외부 호출이 DB 커넥션·트랜잭션을 오래 물지 않는다(F-PSRC-8 완화).</li>
 * </ul>
 * 별도 빈으로 분리한 이유: 같은 클래스 내부 self-invocation은 Spring 프록시를 거치지 않아
 * {@code @Transactional(REQUIRES_NEW)}가 적용되지 않기 때문.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketRegistrationTxService {

	private final MarketRegistrationRepository marketRegistrationRepository;

	/**
	 * 외부 게시 전에 등록행을 PENDING(isSynced=false, identifiers 비움) 상태로 저장한다.
	 * <p>
	 * F-PSRC-13 멱등성: 재게시로 이미 행이 존재하면 그 행을 재사용한다(순차 재호출 멱등).
	 * 동시 재게시(경쟁)로 두 트랜잭션이 모두 "행 없음"을 관측해 둘 다 insert를 시도하면,
	 * {@code sb_market_registration(product_id, market_type)} 유니크 제약에 의해 하나만 성공한다.
	 * 진 쪽은 {@link DataIntegrityViolationException}을 받으므로, 이미 커밋된 상대 행을
	 * 재조회해 재사용한다(멱등 보장).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public MarketRegistration savePending(Long productId, MarketType marketType, String marketProductName) {
		return marketRegistrationRepository.findByProductIdAndMarketType(productId, marketType)
			.orElseGet(() -> insertPending(productId, marketType, marketProductName));
	}

	private MarketRegistration insertPending(Long productId, MarketType marketType, String marketProductName) {
		try {
			return marketRegistrationRepository.save(MarketRegistration.builder()
				.productId(productId)
				.sbProductId(productId)
				.marketType(marketType)
				.marketProductName(marketProductName)
				.marketIdentifiers("{}")
				.marketDetailedInfo("{}")
				.build());
		} catch (DataIntegrityViolationException e) {
			// 동시 재게시 경쟁 — 상대 트랜잭션이 먼저 커밋한 행을 재조회해 재사용한다.
			log.info("[게시-멱등] 등록행 동시 insert 경쟁 감지 — 기존 행 재사용: productId={}, market={}",
				productId, marketType);
			return marketRegistrationRepository.findByProductIdAndMarketType(productId, marketType)
				.orElseThrow(() -> e);
		}
	}

	/**
	 * 외부 게시 성공 후 등록행에 마켓 identifiers를 반영하고 SYNCED로 표시한다.
	 * 이 갱신이 실패해도 {@link #savePending} 단계의 PENDING 행은 이미 커밋돼 있어
	 * 고아가 아니라 복구 가능한 미완료 상태로 남는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markPublished(MarketRegistration registration, String identifiersJson) {
		registration.updateMarketIdentifiers(identifiersJson);
		registration.markSynced();
		marketRegistrationRepository.save(registration);
	}
}
