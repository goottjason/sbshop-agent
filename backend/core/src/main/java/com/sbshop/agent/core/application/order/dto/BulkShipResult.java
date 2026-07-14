package com.sbshop.agent.core.application.order.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

/**
 * 일괄 발송 결과 집계(F-ORD-30 / SP-3).
 *
 * <p>프론트가 {@link BulkConfirmResult}와 동일한 방식으로 표시하므로 필드명을 일치시킨다
 * (successCount/failedCount/failedIds/errors). {@code skippedCount}는 이미 발송·송장 없음 등
 * 정상 스킵을 실패와 구분하기 위해 추가한 필드다.
 */
@Getter
@Builder
public class BulkShipResult {
	private int successCount;
	private int failedCount;
	private int skippedCount;
	private List<Long> failedIds;
	private List<String> errors;
}
