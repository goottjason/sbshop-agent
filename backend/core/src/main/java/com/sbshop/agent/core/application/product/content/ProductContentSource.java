package com.sbshop.agent.core.application.product.content;

import java.util.List;

/** Fetches reviewed source content only; implementations must not mutate products or markets. */
public interface ProductContentSource {
	Fetch fetch(String sourceUrl);

	record Fetch(List<String> sourceImages, List<String> hostedImages, String sanitizedDetailHtml,
		boolean imagesComplete, boolean detailComplete, List<String> notices) {
	}
}
