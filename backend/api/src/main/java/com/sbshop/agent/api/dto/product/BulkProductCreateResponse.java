package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import java.util.List;

/**
 * 상품 일괄 등록 응답(F-PSRC-6). 성공 항목(요청 index + 생성 productId + sbCode)과
 * 실패 항목(요청 index + 식별자 + 사유)을 함께 반환해 요청↔결과 매핑을 가능하게 한다.
 */
public record BulkProductCreateResponse(
	List<Success> succeeded,
	List<Failure> failed) {

	public record Success(int index, Long productId, String sbCode) {
	}

	public record Failure(int index, String baseName, String reason) {
	}

	public static BulkProductCreateResponse from(BulkProductCreateResult result) {
		List<Success> succeeded = result.succeeded().stream()
			.map(s -> new Success(s.index(), s.product().getId(), s.product().getSbCode()))
			.toList();
		List<Failure> failed = result.failed().stream()
			.map(f -> new Failure(f.index(), f.baseName(), f.reason()))
			.toList();
		return new BulkProductCreateResponse(succeeded, failed);
	}
}
