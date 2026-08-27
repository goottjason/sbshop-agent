package com.sbshop.agent.core.application.market;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketRegistrationLookup {

	private final MarketRegistrationRepository marketRegistrationRepository;

	public Optional<MarketRegistration> findUnique(MarketType marketType, String key, String value) {
		if (marketType == null || key == null || key.isBlank() || value == null || value.isBlank()) {
			return Optional.empty();
		}
		String wanted = value.trim();
		List<MarketRegistration> exact = marketRegistrationRepository
			.findIdentifierCandidates(marketType, wanted)
			.stream()
			.filter(reg -> wanted.equals(trimmed(reg.identifier(key))))
			.toList();

		if (exact.isEmpty()) {
			return Optional.empty();
		}
		if (exact.size() > 1) {
			log.warn("[{}] 상품 매칭 중단 — {}={} 인 등록행이 {}건이라 어느 상품인지 확정할 수 없다. "
				+ "잘못 배송하지 않도록 매칭하지 않는다: sbProductId 후보={}",
				marketType, key, wanted, exact.size(),
				exact.stream().map(MarketRegistration::getSbProductId).toList());
			return Optional.empty();
		}
		return Optional.of(exact.get(0));
	}

	private String trimmed(String raw) {
		return raw == null ? null : raw.trim();
	}
}
