package com.sbshop.agent.infrastructure.client.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class HtmlImageExtractor {

	private static final Pattern IMG_PATTERN = Pattern.compile(
		"<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>",
		Pattern.CASE_INSENSITIVE);

	public List<String> extractSkuImages(String html, String sku) {
		if (html == null || html.isBlank() || sku == null || sku.isBlank()) {
			return Collections.emptyList();
		}

		List<String> images = new ArrayList<>();
		Matcher matcher = IMG_PATTERN.matcher(html);
		String lowerSku = sku.toLowerCase();

		while (matcher.find()) {
			String imgUrl = matcher.group(1);
			if (imgUrl.toLowerCase().contains(lowerSku)) {
				images.add(imgUrl);
			}
		}
		Collections.sort(images);
		return images;
	}
}
