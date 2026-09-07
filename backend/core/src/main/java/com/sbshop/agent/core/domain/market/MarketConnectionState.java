package com.sbshop.agent.core.domain.market;

public enum MarketConnectionState {
	LINKED, DETACHED_DELETED, DETACHED_PROHIBITED;

	public boolean detached() {
		return this != LINKED;
	}
}
