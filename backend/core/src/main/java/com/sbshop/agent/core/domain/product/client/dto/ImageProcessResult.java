package com.sbshop.agent.core.domain.product.client.dto;

import java.util.List;

public record ImageProcessResult(
	List<ImageUploadFile> succeeded,
	List<ImageFailure> failed) {
	public record ImageFailure(String ref, String reason) {
	}

	public static ImageProcessResult of(List<ImageUploadFile> succeeded, List<ImageFailure> failed) {
		return new ImageProcessResult(List.copyOf(succeeded), List.copyOf(failed));
	}
}
