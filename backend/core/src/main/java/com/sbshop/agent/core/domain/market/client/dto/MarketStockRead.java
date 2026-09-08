package com.sbshop.agent.core.domain.market.client.dto;

/** Fresh quantity proof for one exactly identified option, not a whole-product sync result. */
public record MarketStockRead(Integer quantity, boolean writable, String reason, String accountReference,
	String optionId, String saleState, String stockState) {
	/** Existing adapters retain quantity-only proof; 11st requires independent sale and inventory states. */
	public MarketStockRead(Integer quantity, boolean writable, String reason, String accountReference,
		String optionId) {
		this(quantity, writable, reason, accountReference, optionId, null, null);
	}
}
