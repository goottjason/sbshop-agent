package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductSanitizer;
import com.sbshop.agent.core.domain.product.component.ProductValidator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPublishUseCase {

	private final ProductReader productReader;
	private final MarketClientRouter marketClientRouter;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ObjectMapper objectMapper;
	private final ProductSanitizer productSanitizer;
	private final ProductValidator productValidator;

	@Transactional
	public void publishToMarket(Long productId, MarketType marketType) {
		Product product = productReader.findById(productId)
				.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));

		if (!marketClientRouter.hasClient(marketType)) {
			throw new IllegalArgumentException("지원하지 않는 마켓입니다: " + marketType);
		}

		productSanitizer.sanitizeForPublish(product);
		productValidator.validateForPublish(product);

		MarketClient client = marketClientRouter.getClient(marketType);
		Map<String, String> identifiers = client.publish(product);

		String identifiersJson;
		try {
			identifiersJson = objectMapper.writeValueAsString(identifiers);
		} catch (Exception e) {
			identifiersJson = "{}";
		}

		MarketRegistration registration = MarketRegistration.builder()
				.productId(productId)
				.sbProductId(productId)
				.marketType(marketType)
				.marketProductName(product.getProductName())
				.marketIdentifiers(identifiersJson)
				.marketDetailedInfo("{}")
				.build();
		registration.markSynced();
		marketRegistrationRepository.save(registration);

		log.info("상품 마켓 등록 완료: productId={}, market={}, identifiers={}", productId, marketType, identifiers);
	}
}
