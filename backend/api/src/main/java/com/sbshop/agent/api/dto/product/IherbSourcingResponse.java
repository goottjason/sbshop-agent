package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.sourcing.dto.SourcingCrawlResult;
import java.util.List;

/**
 * iHerb 소싱 응답(F-PSRC-2). 성공 상품 목록과 실패 목록(URL + 사유)을 함께 반환한다.
 *
 * <p>전건 실패도 200으로 실패 내역을 담아 표면화한다(조용히 누락 금지).
 */
public record IherbSourcingResponse(
	List<ProductSourcingResponse> succeeded,
	List<Failure> failed) {

	public record Failure(String url, String reason) {
	}

	public static IherbSourcingResponse from(SourcingCrawlResult result) {
		List<ProductSourcingResponse> succeeded = result.succeeded().stream()
			.map(ProductSourcingResponse::from)
			.toList();
		List<Failure> failed = result.failed().stream()
			.map(f -> new Failure(f.url(), f.reason()))
			.toList();
		return new IherbSourcingResponse(succeeded, failed);
	}
}
