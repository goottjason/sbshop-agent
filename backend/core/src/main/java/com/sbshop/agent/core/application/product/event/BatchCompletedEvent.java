package com.sbshop.agent.core.application.product.event;

import org.springframework.context.ApplicationEvent;

public class BatchCompletedEvent extends ApplicationEvent {
	private final String batchId;
	private final String actionType;
	private final boolean success;
	private final String message;

	public BatchCompletedEvent(Object source, String batchId, String actionType, boolean success, String message) {
		super(source);
		this.batchId = batchId;
		this.actionType = actionType;
		this.success = success;
		this.message = message;
	}

	public String getBatchId() { return batchId; }
	public String getActionType() { return actionType; }
	public boolean isSuccess() { return success; }
	public String getMessage() { return message; }
}
