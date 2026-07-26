package com.sbshop.agent.core.application.sourcing.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 발굴 1회차 결과 요약. API 응답과 로그에 동시에 쓴다.
 *
 * @param warnings 단계별 실패 사유(차단된 카테고리, 상세 크롤 실패 등). 비어 있지 않으면
 *                 후보 수가 적은 이유를 여기서 설명할 수 있어야 한다.
 */
public record DiscoverySummary(
	LocalDateTime startedAt,
	LocalDateTime finishedAt,
	int crawled,
	int created,
	int updated,
	int excluded,
	int scored,
	int customsBlocked,
	int customsReview,
	int cooldownReleased,
	List<String> warnings) {

	public static DiscoverySummary failed(LocalDateTime startedAt, List<String> warnings) {
		return new DiscoverySummary(startedAt, LocalDateTime.now(),
			0, 0, 0, 0, 0, 0, 0, 0, warnings);
	}
}
