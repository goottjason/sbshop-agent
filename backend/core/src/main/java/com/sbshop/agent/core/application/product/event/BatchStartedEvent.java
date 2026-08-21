package com.sbshop.agent.core.application.product.event;

import org.springframework.context.ApplicationEvent;

public class BatchStartedEvent extends ApplicationEvent {
	private final String batchId;
	private final String actionType;
	private final int count;

	public BatchStartedEvent(Object source, String batchId, String actionType, int count) {
		super(source);
		this.batchId = batchId;
		this.actionType = actionType;
		this.count = count;
	}

	public String getBatchId() {
		return batchId;
	}

	public String getActionType() {
		return actionType;
	}

	public int getCount() {
		return count;
	}
}
