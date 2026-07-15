package com.sbshop.agent.core.domain.product.client.dto;

import java.util.List;

/**
 * F-PROD-16(D-092): 개별 이미지 다운로드/변환의 부분 실패를 집계해 표면화하기 위한 결과.
 *
 * <p>기존 {@code downloadAndConvert}는 성공한 {@link ImageUploadFile}만 반환하고 실패 URL을 조용히
 * 드롭했다. 이 레코드는 성공 파일과 함께 실패 항목({@code ref}=원본 URL/식별자, {@code reason}=실패 사유)을
 * 함께 담아 "성공 N장 / 실패 M장"을 응답에 실을 수 있게 한다.
 */
public record ImageProcessResult(
	List<ImageUploadFile> succeeded,
	List<ImageFailure> failed) {

	/** 개별 이미지 처리 실패. ref=원본 URL 또는 파일명, reason=실패 사유. */
	public record ImageFailure(String ref, String reason) {
	}

	public static ImageProcessResult of(List<ImageUploadFile> succeeded, List<ImageFailure> failed) {
		return new ImageProcessResult(List.copyOf(succeeded), List.copyOf(failed));
	}
}
