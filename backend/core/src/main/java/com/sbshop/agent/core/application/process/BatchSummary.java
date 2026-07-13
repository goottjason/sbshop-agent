package com.sbshop.agent.core.application.process;

/**
 * 배치 진행현황 경량 집계. 2145건 전체 행을 폴링으로 내려보내는 대신 count 쿼리로 산출한다.
 * done = success + failed, percent = total==0 ? 0 : round(done*100/total).
 */
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
		int percent = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
		return new BatchSummary(batchId, total, success, failed, pending, done, percent);
	}
}
