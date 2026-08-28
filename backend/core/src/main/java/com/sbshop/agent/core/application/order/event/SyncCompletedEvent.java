package com.sbshop.agent.core.application.order.event;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.springframework.context.ApplicationEvent;

public class SyncCompletedEvent extends ApplicationEvent {

	public static final int UNMEASURED = -1;

	private final MarketType marketType;
	private final boolean success;
	private final String errorMessage;
	private final int processedCount;
	private final int newCount;

	public SyncCompletedEvent(Object source, MarketType marketType) {
		this(source, marketType, true, null, UNMEASURED, UNMEASURED);
	}

	public SyncCompletedEvent(Object source, MarketType marketType, int processedCount, int newCount) {
		this(source, marketType, true, null, processedCount, newCount);
	}

	public SyncCompletedEvent(Object source, MarketType marketType, boolean success, String errorMessage) {
		this(source, marketType, success, errorMessage, UNMEASURED, UNMEASURED);
	}

	public SyncCompletedEvent(Object source, MarketType marketType, boolean success,
		String errorMessage, int processedCount, int newCount) {
		super(source);
		this.marketType = marketType;
		this.success = success;
		this.errorMessage = errorMessage;
		this.processedCount = processedCount;
		this.newCount = newCount;
	}

	public int getProcessedCount() {
		return processedCount;
	}

	public int getNewCount() {
		return newCount;
	}

	public boolean isMeasured() {
		return processedCount != UNMEASURED && newCount != UNMEASURED;
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
