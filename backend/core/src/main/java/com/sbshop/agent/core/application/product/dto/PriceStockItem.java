package com.sbshop.agent.core.application.product.dto;

import java.math.BigDecimal;

/**
 * 수동 가격/재고 배치의 단일 대상: productId에 price·stock을 쌍으로 묶는다.
 * 병렬 배열(index 위치 매핑)을 대체해 값이 엉뚱한 상품에 적용되는 데이터 오염을 원천 차단한다.
 * price/stock은 null 허용(부분 수정: 지정된 값만 반영).
 */
public record PriceStockItem(Long productId, BigDecimal price, Integer stock) {
}
