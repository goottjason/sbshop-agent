package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;

public record MarketPlusHandoff(MarketType marketType, String cafe24ProductCode) {
}
