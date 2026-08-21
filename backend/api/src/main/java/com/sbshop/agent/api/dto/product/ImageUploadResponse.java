package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import java.util.List;

public record ImageUploadResponse(
	boolean storageUpdated,
	List<MarketOutcome> synced,
	List<MarketOutcome> skipped,
	List<MarketFailure> failed,
	int imagesSucceeded,
	List<ImageFailure> imagesFailed) {

	public record MarketOutcome(String market, String label) {
	}

	public record MarketFailure(String market, String label, String error) {
	}

	public record ImageFailure(String ref, String reason) {
	}

	public static ImageUploadResponse from(MarketRepublishResult result) {
		return from(result, ImageProcessResult.of(List.of(), List.of()), 0);
	}

	public static ImageUploadResponse from(
		MarketRepublishResult result, ImageProcessResult images, int succeededCount) {
		return new ImageUploadResponse(
			true,
			result.synced().stream().map(ImageUploadResponse::outcome).toList(),
			result.skipped().stream().map(ImageUploadResponse::outcome).toList(),
			result.failed().entrySet().stream()
				.map(e -> new MarketFailure(e.getKey().name(), e.getKey().getLabel(), e.getValue()))
				.toList(),
			succeededCount,
			images.failed().stream()
				.map(f -> new ImageFailure(f.ref(), f.reason()))
				.toList());
	}

	private static MarketOutcome outcome(MarketType type) {
		return new MarketOutcome(type.name(), type.getLabel());
	}
}
