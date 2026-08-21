package com.sbshop.agent.core.domain.sourcing.component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VendorProductIdExtractor {
	private static final Pattern IHERB_ID = Pattern.compile("/(?:pr/[^/]+|product)/(\\d+)");

	private VendorProductIdExtractor() {}

	public static String iherbId(String url) {
		if (url == null || url.isBlank())
			return null;
		Matcher m = IHERB_ID.matcher(url);
		return m.find() ? m.group(1) : null;
	}
}
