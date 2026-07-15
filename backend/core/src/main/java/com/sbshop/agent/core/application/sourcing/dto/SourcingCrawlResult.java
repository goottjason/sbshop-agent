package com.sbshop.agent.core.application.sourcing.dto;

import java.util.List;

/**
 * iHerb 소싱 크롤 집계 결과(F-PSRC-2).
 *
 * <p>기존 {@code List<ScrapedProductDto>}는 크롤 실패 URL을 조용히 누락시켜 어느 URL이
 * 왜 실패했는지 알 수 없었다. 성공 항목과 실패 항목(URL + 사유)을 함께 담아 표면화한다.
 *
 * @param succeeded 크롤에 성공한 상품 목록
 * @param failed 크롤에 실패한 URL과 사유 목록
 */
public record SourcingCrawlResult(
	List<ScrapedProductDto> succeeded,
	List<SourcingFailure> failed) {

	public record SourcingFailure(String url, String reason) {
	}
}
