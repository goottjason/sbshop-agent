package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.ShoppingStats;
import java.util.Optional;

/**
 * 국내 쇼핑 시장 조회 포트 (네이버 검색API 쇼핑).
 *
 * <p>경쟁 상품 수(total)와 최저가(lprice)를 준다 — 각각 경쟁강도와 가격경쟁력 신호가 된다.
 * {@link KeywordVolumePort}와 마찬가지로 선택적 의존이다.
 */
public interface ShoppingMarketPort {

	boolean isEnabled();

	Optional<ShoppingStats> lookup(String query);
}
