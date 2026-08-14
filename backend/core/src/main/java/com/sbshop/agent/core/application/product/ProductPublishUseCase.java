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

/**
 * 상품을 특정 마켓에 게시(등록)한다.
 * <p>
 * F-PSRC-14: 되돌릴 수 없는 외부 {@code client.publish()} 부수효과가 조용한 고아를 만들지 않도록
 * 게시 흐름을 <b>PENDING 선-저장 → publish(트랜잭션 밖) → identifiers+SYNCED 갱신</b>으로 나눈다.
 * DB 쓰기 두 단계는 {@link MarketRegistrationTxService}가 각각 독립 트랜잭션으로 커밋한다.
 * 이 클래스 자체는 트랜잭션을 열지 않는다(외부 호출을 트랜잭션이 감싸지 않게 하기 위함).
 * <p>
 * 이전 구조는 하나의 {@code @Transactional} 안에서 {@code publish()} <em>후</em> save를 했다.
 * save/커밋이 실패하면 마켓엔 상품이 올라갔는데 DB엔 등록이 없는 고아가 됐고, @Transactional은
 * 외부 게시를 롤백하지 못한다. 새 구조에서는 게시가 성공하면 최소한 PENDING 행 + identifiers가
 * DB에 남아 복구 가능한 상태가 된다.
 * <p>
 * (F-PSRC-13 재게시 시 중복 등록 방지/멱등성은 이번 범위 밖 —
 * {@link MarketRegistrationTxService#savePending}이 기존 행을 재사용하는 선에서만 다룬다.)
 */
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

	/**
	 * 결함 B(등록가 20%대 고평가) 수정: 호출자가 마진율·쿠폰율·최소마진을 직접 넘겨 등록가 산정에
	 * 반영할 수 있게 한다. {@code pricingOverrides}가 null이면 기존 동작(오버라이드 없음)과 같다.
	 */
	public MarketPublishOutcome publishToMarket(Long productId, MarketType marketType,
		MarketSalePriceOverrides pricingOverrides) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));

		// G마켓·옥션에는 상품등록 API가 없다(ESM은 Cafe24 마켓플러스 경유). 조용히 실패하는 대신 이유를 말한다.
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

		// 1) 외부 게시 전에 PENDING 등록행을 먼저 커밋해 상태를 확보한다(별도 트랜잭션).
		MarketRegistration registration =
			registrationTxService.savePending(productId, marketType, product.getProductName());

		// 2) 되돌릴 수 없는 외부 게시 — 트랜잭션 밖에서 호출.
		//    D-094: 등록 순간부터 그 마켓의 실수수료 반영가로 올린다. 기준가(쿠팡 기준)로 올리면
		//    다음 재가격 배치까지 수수료가 다른 마켓은 목표 마진을 벗어난 가격으로 팔린다.
		BigDecimal salePrice = pricingOverrides == null
			? marketSalePriceResolver.resolveForProduct(product, marketType)
			: marketSalePriceResolver.resolveForProduct(product, marketType, pricingOverrides);
		MarketPublishContext context = new MarketPublishContext(
			null, null, salePrice, List.of(), Map.of(), Map.of());
		Map<String, String> identifiers = client.publish(product, context);
		String identifiersJson = toJson(identifiers);

		// 3) 게시 성공 후 identifiers + SYNCED 갱신(별도 트랜잭션).
		//    이 갱신이 실패해도 (1)의 PENDING 행은 이미 커밋돼 있으므로 고아가 아니라
		//    복구 가능한 미완료 상태다. 마켓 identifiers를 복구용 ERROR 로그로 남기고 실패를 표면화한다.
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
