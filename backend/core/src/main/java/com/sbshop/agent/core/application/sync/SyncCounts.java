package com.sbshop.agent.core.application.sync;

public record SyncCounts(int processed, int created) {

	public static SyncCounts none() {
		return new SyncCounts(0, 0);
	}

	public SyncCounts plusProcessed(boolean created) {
		return new SyncCounts(this.processed + 1, this.created + (created ? 1 : 0));
	}
}
