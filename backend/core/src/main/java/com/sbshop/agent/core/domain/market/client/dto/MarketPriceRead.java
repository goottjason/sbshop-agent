package com.sbshop.agent.core.domain.market.client.dto;

import java.math.BigDecimal;

/** A fresh price observation; never inferred from an outbound request or local cache. */
public record MarketPriceRead(BigDecimal value, boolean writable, String reason, String accountReference) {
}
