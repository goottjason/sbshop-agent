package com.sbshop.agent.core.domain.product.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class HtmlImageReplacer {
	public String replaceImagesBySku(String originalHtml, String sku, List<String> hostedImages) {
		return replaceImagesBySku(originalHtml, sku, List.of(), hostedImages);
	}

	public String replaceImagesBySku(String originalHtml, String sku, List<String> previousImages,
		List<String> hostedImages) {
		if (originalHtml == null || originalHtml.isEmpty()) {
			return originalHtml;
		}

		List<String> sources = new ArrayList<>();
		if (sku != null && !sku.isBlank())
			sources.add("(?i:[^\"']*" + Pattern.quote(sku) + "[^\"']*)");
		if (previousImages != null)
			previousImages.stream().filter(url -> url != null && !url.isBlank())
				.map(Pattern::quote).forEach(sources::add);
		if (sources.isEmpty())
			return originalHtml;

		String regex = "(?i:<img)\\b[^>]*\\s+(?i:src)\\s*=\\s*([\"'])(?:" + String.join("|", sources)
			+ ")\\1[^>]*>(?:\\s*(?i:<br)\\s*/?>\\s*)*";
		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(originalHtml);

		StringBuffer sb = new StringBuffer();
		boolean isFirstMatch = true;

		while (matcher.find()) {
			if (isFirstMatch) {
				StringBuilder newTags = new StringBuilder();
				for (String newUrl : hostedImages) {
					newTags.append(String.format(
						"<img src=\"%s\" style=\"margin-left: auto; margin-right: auto; display: block;\"><br /><br /><br /><br />",
						newUrl));
				}
				matcher.appendReplacement(sb, Matcher.quoteReplacement(newTags.toString()));
				isFirstMatch = false;
			} else {
				matcher.appendReplacement(sb, "");
			}
		}
		matcher.appendTail(sb);

		return sb.toString();
	}
}
