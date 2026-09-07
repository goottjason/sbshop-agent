package com.sbshop.agent.core.domain.market.client.dto;

/** Fresh quantity proof for one exactly identified option, not a whole-product sync result. */
public record MarketStockRead(Integer quantity, boolean writable, String reason, String accountReference,
	String optionId) {
}
