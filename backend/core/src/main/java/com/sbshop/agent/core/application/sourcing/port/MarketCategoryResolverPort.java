package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.domain.order.enums.MarketType;

/**
 * 마켓 카테고리 자동 해석 포트. 마켓마다 하나씩 구현한다.
 *
 * <p>카테고리는 등록 필수필드이면서 마켓마다 체계가 완전히 다르다. 잘못 넣으면 등록은 되지만
 * 노출이 안 되거나 심사에서 반려되므로, 확신이 없으면 {@code confident=false}로 돌려
 * 검수 화면에서 사람이 고르게 한다.
 */
public interface MarketCategoryResolverPort {

	MarketType market();

	/**
	 * @param categoryHint LLM이 준 "대분류 &gt; 소분류" 힌트 (없을 수 있음)
	 * @param productName  한글 상품명
	 * @param brand        브랜드
	 */
	MarketCategory resolve(String categoryHint, String productName, String brand);
}
