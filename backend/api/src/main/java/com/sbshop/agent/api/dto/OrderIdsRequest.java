package com.sbshop.agent.api.dto;

import java.util.List;

/**
 * 주문 ID 목록 일괄 요청 DTO. confirm/batch·cancel/batch 공용(F-ORD-11/20).
 * JSON 계약: {@code {"orderIds":[1,2,...]}} — 기존 Map 바인딩과 동일 필드명/형태 유지.
 */
public record OrderIdsRequest(List<Long> orderIds) {
}
