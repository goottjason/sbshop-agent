package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.product.Product;
import java.util.List;

/**
 * 상품 일괄 등록 집계 결과(F-PSRC-6).
 *
 * <p>기존 {@code List<Product>}는 항목별 생성 실패를 응답에서 누락시켜 요청↔결과 매핑이
 * 불가능했다. 성공 항목(요청 index + 생성된 Product)과 실패 항목(요청 index + 식별자 + 사유)을
 * 함께 담아 표면화한다.
 *
 * @param succeeded 생성에 성공한 항목 목록
 * @param failed 생성에 실패한 항목 목록
 */
public record BulkProductCreateResult(
	List<Success> succeeded,
	List<Failure> failed) {

	public record Success(int index, Product product) {
	}

	public record Failure(int index, String baseName, String reason) {
	}
}
