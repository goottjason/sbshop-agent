package com.sbshop.agent.core.domain.market.client;

import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.util.List;
import java.util.Map;

public interface MarketClient {

	MarketType getSupportedMarket();

	Map<String, String> publish(Product product);

	/**
	 * 검수된 마켓별 등록 데이터를 함께 받는 게시 오버로드(신규 상품 등록 자동화 경로).
	 *
	 * <p>카테고리·검색키워드·상품고시정보·출고지 코드처럼 {@code Product}에 없고 마켓마다 다른
	 * 값들이 {@link MarketPublishContext}로 온다. 기본 구현은 컨텍스트를 무시하고 기존
	 * {@link #publish(Product)}로 위임한다 — 어댑터를 하나씩 옮겨도 나머지가 깨지지 않는다.
	 */
	default Map<String, String> publish(Product product, MarketPublishContext context) {
		return publish(product);
	}

	MarketItemInfo extractMarketItem(String marketItemId);

	MarketItemInfo parseLocalData(Map<String, Object> rawData);

	Map<String, Object> syncPriceAndStock(
		String marketItemId,
		Map<String, Object> currentRawData,
		Integer price,
		int quantity,
		boolean soldOut);

	/**
	 * 단위가격(가격표시제) 등 상품 속성이 필요한 마켓(스마트스토어)을 위해 {@link Product}를 함께 받는 오버로드.
	 * 기본 구현은 상품을 무시하고 5-인자 경로로 위임한다 — 스토어만 override 해 unitCapacity 등을 채운다.
	 */
	default Map<String, Object> syncPriceAndStock(
		String marketItemId,
		Map<String, Object> currentRawData,
		Integer price,
		int quantity,
		boolean soldOut,
		Product product) {
		return syncPriceAndStock(marketItemId, currentRawData, price, quantity, soldOut);
	}

	/**
	 * 상품 이미지/상세HTML을 마켓에 재게시한다.
	 *
	 * @param product 원본 상품(D-092: 11번가 등 전체 상품 전문 재구성이 필요한 마켓이 buildProductXml 재사용).
	 *                조회 API로 전체 편집 전문을 얻을 수 없는 마켓은 이 Product로 전문을 재구성한다.
	 */
	Map<String, Object> syncImagesAndHtml(
		Product product,
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

	/**
	 * D-096: 판매자 즉시할인 정책을 제거한다(저수수료 마켓의 중복 할인 정리, 일회성).
	 * 마켓별 가격이 이미 각 수수료에 맞게 산정되므로 별도 즉시할인이 겹치면 이중할인 손해가 난다.
	 *
	 * @param marketItemId 마켓 상품코드(스토어=originProductNo)
	 * @param dryRun       true면 현재 즉시할인만 조회해 보고(수정하지 않음)
	 * @return 발견(또는 제거)한 즉시할인 설명. 즉시할인이 없으면 empty.
	 *         <p>기본 구현은 no-op(empty) — 지원 마켓(스토어)만 override.
	 */
	default java.util.Optional<String> removeSellerImmediateDiscount(String marketItemId, boolean dryRun) {
		return java.util.Optional.empty();
	}
}
