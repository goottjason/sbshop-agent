package com.sbshop.agent.api.dto.batch;

import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import java.util.List;

public record ManualUpdateAllRequest(
	List<Long> productIds,
	List<ProductUpdateCommand> commands) {
}
