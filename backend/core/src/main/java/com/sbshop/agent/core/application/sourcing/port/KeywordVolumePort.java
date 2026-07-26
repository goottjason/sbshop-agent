package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.KeywordVolume;
import java.util.List;

/**
 * 키워드 월간 검색량 조회 포트 (네이버 검색광고 keywordstool).
 *
 * <p><b>선택적 의존이다.</b> 자격증명이 없으면 {@link #isEnabled()}가 false이고,
 * 스코어링은 검색량 가중치를 빼고 나머지 신호로 정규화한다 — 없다고 파이프라인이 멈추지 않는다.
 */
public interface KeywordVolumePort {

	boolean isEnabled();

	/**
	 * 시드 키워드와 연관 키워드들의 검색량. 첫 원소가 시드 자신인 것이 보장되지는 않는다.
	 * 조회 실패 시 빈 목록(예외 아님) — 신호 하나가 없다고 후보 전체를 버릴 이유가 없다.
	 */
	List<KeywordVolume> lookup(String seedKeyword);
}
