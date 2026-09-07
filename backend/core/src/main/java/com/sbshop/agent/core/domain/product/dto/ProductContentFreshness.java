package com.sbshop.agent.core.domain.product.dto;

import java.time.Instant;

public record ProductContentFreshness(Instant imagesCollectedAt, Instant imagesAppliedAt,
	Instant detailHtmlCollectedAt, Instant detailHtmlAppliedAt) {
	public static final ProductContentFreshness EMPTY = new ProductContentFreshness(null, null, null, null);
}
