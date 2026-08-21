package com.sbshop.agent.core.application.product.event;

import org.springframework.context.ApplicationEvent;

/**
 * D-089: 배치 시작을 전 클라이언트에 알리기 위한 이벤트. SseNotificationController가 수신해
 * BATCH_STARTED로 방송하면, 배치를 개시하지 않은 다른 브라우저도 batchId를 받아 진행바를 공유한다.
 */
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
