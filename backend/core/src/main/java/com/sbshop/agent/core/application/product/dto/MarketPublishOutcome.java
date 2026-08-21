package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;

public record MarketPublishOutcome(MarketType marketType, Map<String, String> identifiers, boolean synced) {
}
