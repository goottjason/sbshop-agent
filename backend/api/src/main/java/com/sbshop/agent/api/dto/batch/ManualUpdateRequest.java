package com.sbshop.agent.api.dto.batch;

import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import java.util.List;

public record ManualUpdateRequest(
	List<PriceStockItem> items) {
}
