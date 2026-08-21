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

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketRegistrationTxService {
	private final MarketRegistrationRepository marketRegistrationRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public MarketRegistration savePending(Long productId, MarketType marketType, String marketProductName) {
		return marketRegistrationRepository.findByProductIdAndMarketType(productId, marketType)
			.orElseGet(() -> insertPending(productId, marketType, marketProductName));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markPublished(MarketRegistration registration, String identifiersJson) {
		registration.updateMarketIdentifiers(identifiersJson);
		registration.markSynced();
		marketRegistrationRepository.save(registration);
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
			log.info("[게시-멱등] 등록행 동시 insert 경쟁 감지 — 기존 행 재사용: productId={}, market={}",
				productId, marketType);
			return marketRegistrationRepository.findByProductIdAndMarketType(productId, marketType)
				.orElseThrow(() -> e);
		}
	}
}
