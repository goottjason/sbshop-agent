package com.sbshop.agent.api.dto.batch;

import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import java.util.List;

/**
 * F-BATCH-M1: productId·price·stock을 병렬 배열이 아닌 쌍 리스트(items)로 받는다.
 * 병렬 배열 index 매핑은 순서가 어긋나면 엉뚱한 상품에 값이 적용되는 데이터 오염을 유발했다.
 */
public record ManualUpdateRequest(
	List<PriceStockItem> items) {
}
