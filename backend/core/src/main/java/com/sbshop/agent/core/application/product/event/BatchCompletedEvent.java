package com.sbshop.agent.core.application.product.event;

import org.springframework.context.ApplicationEvent;

public class BatchCompletedEvent extends ApplicationEvent {
	private final String batchId;
	private final String actionType;
	private final int failCount;
	private final int partialCount;
	private final String message;

	public BatchCompletedEvent(Object source, String batchId, String actionType,
		int failCount, int partialCount, String message) {
		super(source);
		this.batchId = batchId;
		this.actionType = actionType;
		this.failCount = failCount;
		this.partialCount = partialCount;
		this.message = message;
	}

	public BatchCompletedEvent(Object source, String batchId, String actionType, boolean success, String message) {
		this(source, batchId, actionType, success ? 0 : 1, 0, message);
	}

	public String getBatchId() {
		return batchId;
	}

	public String getActionType() {
		return actionType;
	}

	public boolean isSuccess() {
		return failCount == 0 && partialCount == 0;
	}

	public int getFailCount() {
		return failCount;
	}

	public int getPartialCount() {
		return partialCount;
	}

	public String getMessage() {
		return message;
	}
}
