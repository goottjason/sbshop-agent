package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPricePreviewUseCase {
	private final ProductReader productReader;
	private final MarketSalePriceResolver resolver;
	private final MarketClientRouter router;

	public enum Status {
		CALCULATED, FALLBACK, FAILED
	}
	public record Item(MarketType market, Status status, String roundedPrice, String minimumPrice,
		String salePrice, boolean minimumAdjusted, String reason) {
	}
	public record Response(String mode, Long productId, String sbCode, Instant generatedAt, List<Item> items) {
	}

	@Transactional(readOnly = true)
	public Response preview(Long productId) {
		Product product = productReader.findById(productId).filter(p -> p.getDeletedAt() == null)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		List<Item> items = Arrays.stream(MarketType.values()).filter(router::hasClient)
			.map(market -> previewMarket(product, market)).toList();
		return new Response("READ_ONLY", productId, product.getSbCode(), Instant.now(), items);
	}

	private Item previewMarket(Product product, MarketType market) {
		try {
			var explanation = resolver.explainForProduct(product, market, MarketSalePriceOverrides.EMPTY);
			var result = explanation.result();
			if (result == null || result.salePrice().signum() <= 0) {
				return failed(market, "계산에 사용할 양수의 판매가가 없습니다.");
			}
			boolean fallback = explanation.basis() == MarketSalePriceResolver.Basis.STORED_PRICE_FALLBACK;
			String reason = fallback ? "원가·마진 자료 부족으로 저장된 기준가를 반올림했습니다. 최소마진은 검증하지 못했습니다."
				: result.reason() + (result.minimumPrice() == null ? " · 최소마진 설정 없음" : "");
			return new Item(market, fallback ? Status.FALLBACK : Status.CALCULATED, text(result.roundedPrice()),
				text(result.minimumPrice()), text(result.salePrice()), result.minimumAdjusted(), reason);
		} catch (RuntimeException e) {
			log.warn("[가격미리보기] 계산 실패 sbCode={} market={}", product.getSbCode(), market, e);
			return failed(market, e instanceof IllegalArgumentException || e instanceof IllegalStateException
				? e.getMessage() : "가격 정책을 불러오거나 계산하지 못했습니다.");
		}
	}

	private Item failed(MarketType market, String reason) {
		return new Item(market, Status.FAILED, null, null, null, false,
			reason == null ? "가격을 계산하지 못했습니다." : reason);
	}

	private static String text(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}
}
