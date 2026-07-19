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

	/**
	 * 상품 그리드 링크에 필요한 부가 식별자를 마켓 API로 조회한다(백필용, best-effort).
	 * <p>입력 {@code sourceIdentifier}는 마켓별 조회 키(쿠팡=sellerProductId, 스토어=originProductNo).
	 * 반환값은 백필 대상 식별자 값(쿠팡=productId, 스토어=channelProductNo). 미지원/미확보면 empty.
	 * <p>기본 구현은 no-op(empty) — 링크 식별자 확보가 필요한 마켓만 override 한다.
	 */
	default java.util.Optional<String> fetchLinkIdentifier(String sourceIdentifier) {
		return java.util.Optional.empty();
	}

	/**
	 * 백필용 배치 조회: 여러 소스 식별자를 마켓 API로 한 번에 조회한다(rate limit 회피).
	 * 반환 맵 키=소스 식별자, 값=백필 대상 식별자 값(미확보 건은 미포함).
	 * <p>기본 구현은 단건 {@link #fetchLinkIdentifier}를 반복한다 —
	 * 배치 API가 있는 마켓(스토어 상품검색)만 override 해 1회 호출로 최적화한다.
	 */
	default Map<String, String> fetchLinkIdentifiers(List<String> sourceIdentifiers) {
		Map<String, String> out = new java.util.HashMap<>();
		if (sourceIdentifiers == null) {
			return out;
		}
		for (String s : sourceIdentifiers) {
			fetchLinkIdentifier(s).ifPresent(v -> out.put(s, v));
		}
		return out;
	}

	/**
	 * 백필용 전체 스캔: 마켓 전 상품을 순회해 (소스 식별자 → 링크 식별자) 맵을 통째로 구축한다.
	 * <p>단건/배치 조회가 필터 미지원 등으로 부적합한 마켓(스토어 상품검색)에서 override 한다.
	 * 기본은 {@code null}(미지원) — 백필 서비스는 null이면 단건/청크 경로를 쓴다.
	 * @param throttleMs 페이지 간 지연(rate limit 회피)
	 */
	default Map<String, String> fetchAllLinkIdentifiers(long throttleMs) {
		return null;
	}
}
