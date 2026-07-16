package com.sbshop.agent.core.application.market;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketRegistrationService {

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;
	private final ProductReader productReader;

	public List<MarketRegistration> getRegistrations(Long productId) {
		productReader.findById(productId)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		return marketRegistrationRepository.findByProductId(productId);
	}

	public MarketRegistration getLocalData(Long productId, String marketType) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		return marketRegistrationRepository
			.findByProductIdAndMarketType(productId, type)
			.orElseThrow(() -> new IllegalArgumentException("마켓 등록 정보 없음: " + marketType));
	}

	public MarketItemInfo syncMarketLive(Long productId, String marketType) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		MarketRegistration reg = marketRegistrationRepository
			.findByProductIdAndMarketType(productId, type)
			.orElseThrow(() -> new IllegalArgumentException("마켓 등록 정보 없음: " + marketType));

		String marketItemId = reg.extractVendorItemId();
		if (marketItemId == null || marketItemId.isEmpty()) {
			marketItemId = String.valueOf(reg.getProductId());
		}

		MarketClient client = marketClientRouter.getClient(type);
		return client.extractMarketItem(marketItemId);
	}
}
