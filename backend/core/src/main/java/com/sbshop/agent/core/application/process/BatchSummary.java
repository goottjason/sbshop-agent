package com.sbshop.agent.core.application.process;

public record BatchSummary(
	String batchId,
	long total,
	long success,
	long failed,
	long pending,
	long done,
	int percent) {

	public static BatchSummary of(String batchId, long total, long success, long failed) {
		long done = success + failed;
		long pending = total - done;
		int percent = total == 0 ? 0 : (int)Math.round(done * 100.0 / total);
		return new BatchSummary(batchId, total, success, failed, pending, done, percent);
	}
}
