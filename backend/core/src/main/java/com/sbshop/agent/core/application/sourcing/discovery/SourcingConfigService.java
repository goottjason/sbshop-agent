package com.sbshop.agent.core.application.sourcing.discovery;

import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import com.sbshop.agent.core.domain.sourcing.repository.SourcingConfigRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SourcingConfigService {
	private final SourcingConfigRepository repository;

	@Transactional
	public SourcingConfig getOrCreate() {
		return repository.findFirstByOrderByIdAsc()
			.orElseGet(() -> {
				log.info("[소싱설정] 설정 행이 없어 기본값으로 생성합니다.");
				return repository.save(SourcingConfig.createDefault());
			});
	}

	@Transactional
	public SourcingConfig update(Integer recommendCount, String categories, Integer pagesPerCategory,
		String scoreWeights, Boolean profitGuardEnabled, BigDecimal targetMarginRate,
		BigDecimal minMarginPrice, BigDecimal maxPriceRatio, BigDecimal couponRate,
		Integer rejectCooldownDays, Boolean excludeSponsored, Integer minReviewCount,
		BigDecimal minRating, Boolean scheduleEnabled, String scheduleCron) {
		SourcingConfig config = getOrCreate();
		config.update(recommendCount, categories, pagesPerCategory, scoreWeights, profitGuardEnabled,
			targetMarginRate, minMarginPrice, maxPriceRatio, couponRate, rejectCooldownDays,
			excludeSponsored, minReviewCount, minRating, scheduleEnabled, scheduleCron);
		return repository.save(config);
	}
}
