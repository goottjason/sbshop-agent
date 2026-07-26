package com.sbshop.agent.core.application.sourcing.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 반입차단 원료·성분 1건 (외부 원천 → 도메인 경계 DTO).
 *
 * @param aliases     기타명칭. 원천이 주지 않으면 빈 목록이며, 보강 시드가 채운다.
 * @param releasedOn  지정 해제일. null이면 현재 차단중.
 */
public record BannedIngredientDto(
	String nameKo,
	String nameEn,
	List<String> aliases,
	LocalDate designatedOn,
	LocalDate releasedOn,
	String reason) {
}
