package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.BannedIngredientDto;
import java.util.List;

/**
 * 해외직구식품 반입차단 원료·성분 목록 원천.
 *
 * <p>구현: 식품안전나라(foodsafetykorea.go.kr) 공개 목록 — 인증키가 필요 없다.
 */
public interface BannedIngredientSourcePort {

	/** 전체 목록. 원천 장애 시 예외를 던진다(호출측이 마지막 성공본을 유지). */
	List<BannedIngredientDto> fetchAll();
}
