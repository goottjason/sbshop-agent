package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Map;

/**
 * 상품 변경(가격/재고·이미지 등)을 연동 마켓에 전파한 결과.
 * synced/skipped = 마켓 목록, failed = 마켓별 실패 사유. 단건(ProductManageUseCase)·배치(BatchPriceStockService) 공용.
 */
public record MarketRepublishResult(
	List<MarketType> synced,
	List<MarketType> skipped,
	Map<MarketType, String> failed) {
}
