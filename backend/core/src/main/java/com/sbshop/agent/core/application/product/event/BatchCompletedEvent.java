package com.sbshop.agent.core.application.product.event;

import org.springframework.context.ApplicationEvent;

public class BatchCompletedEvent extends ApplicationEvent {
	private final String batchId;
	private final boolean success;
	private final String message;

	public BatchCompletedEvent(Object source, String batchId, boolean success, String message) {
		super(source);
		this.batchId = batchId;
		this.success = success;
		this.message = message;
	}

	public String getBatchId() { return batchId; }
	public boolean isSuccess() { return success; }
	public String getMessage() { return message; }
}
