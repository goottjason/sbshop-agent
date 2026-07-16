package com.sbshop.agent.core.domain.market.client;

import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.util.List;
import java.util.Map;

public interface MarketClient {

	MarketType getSupportedMarket();

	Map<String, String> publish(Product product);

	MarketItemInfo extractMarketItem(String marketItemId);

	MarketItemInfo parseLocalData(Map<String, Object> rawData);

	Map<String, Object> syncPriceAndStock(
		String marketItemId,
		Map<String, Object> currentRawData,
		Integer price,
		int quantity,
		boolean soldOut);

	Map<String, Object> syncImagesAndHtml(
		String marketItemId,
		Map<String, Object> currentRawData,
		List<String> hostedImages,
		String newDetailHtml);

	/**
	 * 외부 마켓에서 해당 상품 리스팅을 삭제한다(완전 삭제, F-PROD-27/28).
	 *
	 * <p>실패 시 예외를 던지며, 오케스트레이터가 마켓별로 수집해 best-effort로 처리한다
	 * (마켓 삭제 실패해도 우리 DB의 등록행·Product 삭제는 진행). 마켓이 주문이력 등으로 하드삭제를
	 * 거부하면 그 오류가 예외로 표면화된다.
	 *
	 * <p>기본 구현은 미지원 예외를 던진다 — 각 마켓 어댑터가 반드시 override 한다.
	 */
	default void deleteFromMarket(String marketItemId) {
		throw new UnsupportedOperationException(
			getSupportedMarket() + " 삭제 API 미구현");
	}
}
