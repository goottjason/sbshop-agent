package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;

/**
 * 마켓 게시 1건의 결과.
 *
 * @param marketType  게시한 마켓
 * @param identifiers 마켓이 돌려준 식별자(쿠팡 sellerProductId, 스토어 originProductNo 등)
 * @param synced      등록행이 SYNCED로 갱신됐는지. 마켓플러스 전송처럼 "접수만 된" 경우 false.
 */
public record MarketPublishOutcome(MarketType marketType, Map<String, String> identifiers, boolean synced) {
}
