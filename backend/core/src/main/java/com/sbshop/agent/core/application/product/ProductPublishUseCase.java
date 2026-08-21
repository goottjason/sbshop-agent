package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductSanitizer;
import com.sbshop.agent.core.domain.product.component.ProductValidator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPublishUseCase {
	private final ProductReader productReader;
	private final MarketClientRouter marketClientRouter;
	private final MarketRegistrationTxService registrationTxService;
	private final ObjectMapper objectMapper;
	private final ProductSanitizer productSanitizer;
	private final ProductValidator productValidator;
	private final MarketSalePriceResolver marketSalePriceResolver;

	public MarketPublishOutcome publishToMarket(Long productId, MarketType marketType) {
		return publishToMarket(productId, marketType, null);
	}

	public MarketPublishOutcome publishToMarket(Long productId, MarketType marketType,
		MarketSalePriceOverrides pricingOverrides) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));

		if (marketType == MarketType.GMARKET || marketType == MarketType.AUCTION) {
			throw new IllegalStateException(
				"G마켓·옥션은 API 등록을 지원하지 않습니다 — 마켓플러스에서 전송해야 합니다");
		}

		if (!marketClientRouter.hasClient(marketType)) {
			throw new IllegalArgumentException("지원하지 않는 마켓입니다: " + marketType);
		}

		productSanitizer.sanitizeForPublish(product);
		productValidator.validateForPublish(product);

		MarketClient client = marketClientRouter.getClient(marketType);

		MarketRegistration registration = registrationTxService.savePending(productId, marketType,
			product.getProductName());

		BigDecimal salePrice = pricingOverrides == null
			? marketSalePriceResolver.resolveForProduct(product, marketType)
			: marketSalePriceResolver.resolveForProduct(product, marketType, pricingOverrides);
		MarketPublishContext context = new MarketPublishContext(
			null, null, salePrice, List.of(), Map.of(), Map.of());
		Map<String, String> identifiers = client.publish(product, context);
		String identifiersJson = toJson(identifiers);

		try {
			registrationTxService.markPublished(registration, identifiersJson);
		} catch (RuntimeException e) {
			log.error("[게시-복구필요] 마켓 게시는 성공했으나 등록행 갱신 실패 — PENDING 행 존재, 수동/재시도 복구 필요: "
				+ "productId={}, market={}, identifiers={}", productId, marketType, identifiersJson, e);
			throw e;
		}

		log.info("상품 마켓 등록 완료: productId={}, market={}, identifiers={}", productId, marketType, identifiers);
		return new MarketPublishOutcome(marketType, identifiers, true);
	}

	private String toJson(Map<String, String> identifiers) {
		try {
			return objectMapper.writeValueAsString(identifiers);
		} catch (Exception e) {
			return "{}";
		}
	}
}
