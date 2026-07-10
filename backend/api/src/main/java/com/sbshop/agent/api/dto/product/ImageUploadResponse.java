package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.product.ProductManageUseCase.MarketRepublishResult;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;

/**
 * D-049(반려 재수정): 이미지/HTML 업로드 응답 계약.
 *
 * <p>자사 DB/R2 갱신 성공 여부({@code storageUpdated})와 마켓별 재게시 결과를 분리해 전달한다.
 * 이 DTO가 본문에 실려 반환된다는 것 자체가 자사 저장(R2/DB)은 성공했음을 의미한다
 * (저장 단계 실패는 {@code updateImagesAndHtml}에서 예외로 전파되어 4xx/5xx가 되고 본문이 생성되지 않음).
 * 라이브 마켓 쓰기의 부분 실패({@code failed})가 조용히 삼켜지지 않도록 프론트로 표면화하기 위한 계약.
 */
public record ImageUploadResponse(
	boolean storageUpdated,
	List<MarketOutcome> synced,
	List<MarketOutcome> skipped,
	List<MarketFailure> failed) {

	/** 재게시 성공/스킵 마켓. market=MarketType.name()(프론트 키), label=한글 표기. */
	public record MarketOutcome(String market, String label) {
	}

	/** 재게시 실패 마켓. error=마켓 API가 반환한 실패 사유. */
	public record MarketFailure(String market, String label, String error) {
	}

	public static ImageUploadResponse from(MarketRepublishResult result) {
		return new ImageUploadResponse(
			true,
			result.synced().stream().map(ImageUploadResponse::outcome).toList(),
			result.skipped().stream().map(ImageUploadResponse::outcome).toList(),
			result.failed().entrySet().stream()
				.map(e -> new MarketFailure(e.getKey().name(), e.getKey().getLabel(), e.getValue()))
				.toList());
	}

	private static MarketOutcome outcome(MarketType type) {
		return new MarketOutcome(type.name(), type.getLabel());
	}
}
