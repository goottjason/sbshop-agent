package com.sbshop.agent.core.application.sourcing.dto;

public record KeywordVolume(String keyword, int pcCount, int mobileCount, String competitionIndex) {
	public int total() {
		return pcCount + mobileCount;
	}
}
