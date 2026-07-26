package com.sbshop.agent.core.application.sourcing.dto;

/**
 * 키워드 1건의 월간 검색량.
 *
 * @param competitionIndex 네이버 광고 경쟁정도("높음"/"중간"/"낮음"). 참고용.
 */
public record KeywordVolume(String keyword, int pcCount, int mobileCount, String competitionIndex) {

	public int total() {
		return pcCount + mobileCount;
	}
}
