package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.springframework.context.ApplicationEvent;

public class SyncCompletedEvent extends ApplicationEvent {
	private final MarketType marketType;
	private final boolean success;
	private final String errorMessage;

	public SyncCompletedEvent(Object source, MarketType marketType) {
		this(source, marketType, true, null);
	}

	public SyncCompletedEvent(Object source, MarketType marketType, boolean success, String errorMessage) {
		super(source);
		this.marketType = marketType;
		this.success = success;
		this.errorMessage = errorMessage;
	}

	public MarketType getMarketType() {
		return marketType;
	}

	public boolean isSuccess() {
		return success;
	}

	public String getErrorMessage() {
		return errorMessage;
	}
}
