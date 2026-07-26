package com.sbshop.agent.core.application.sourcing.dto;

import java.util.List;

/**
 * 발굴 크롤 결과.
 *
 * <p>페이지 단위 실패를 {@code failures}로 함께 돌려준다 — 일부 카테고리가 차단당했는데
 * "후보가 적다"로 오인하면 추천 품질이 조용히 무너진다.
 *
 * @param failures "supplements p2: bot/Cloudflare 차단 의심" 형태의 사유 목록
 */
public record DiscoveryCrawlResult(
	List<DiscoveredCandidateDto> candidates,
	List<String> failures) {

	public static DiscoveryCrawlResult empty(String failure) {
		return new DiscoveryCrawlResult(List.of(), List.of(failure));
	}
}
