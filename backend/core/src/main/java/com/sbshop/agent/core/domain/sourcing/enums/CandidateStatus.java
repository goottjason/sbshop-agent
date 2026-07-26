package com.sbshop.agent.core.domain.sourcing.enums;

/**
 * 소싱 후보의 파이프라인 상태.
 *
 * <p>{@code BaseEntity.status}(RecordStatus)와는 별개다 — 그쪽은 논리삭제용이고,
 * 이쪽은 발굴 → 검수 → 등록으로 이어지는 진행 단계를 나타낸다.
 */
public enum CandidateStatus {

	/** 발굴 직후. 아직 통관 게이트·스코어링을 거치지 않았다. */
	NEW,

	/** 통관 PASS/REVIEW + 스코어링 완료 — 추천 목록에 노출되는 유일한 상태. */
	SCORED,

	/**
	 * 파이프라인이 자동으로 걸러냈다(이미 등록됨·품절·단종·통관 BLOCKED·수익성 미달).
	 * 사유는 {@code excludeReason}에 남긴다. 재수집 때 조건이 바뀌면 다시 SCORED로 올라갈 수 있다.
	 */
	EXCLUDED,

	/** 사용자가 명시적으로 거절했다. 쿨다운 기간 동안 재추천하지 않는다. */
	REJECTED,

	/** 사용자가 선택해 등록 초안이 만들어졌다. */
	DRAFTED,

	/** 초안이 마켓에 등록 완료됐다. */
	PUBLISHED
}
