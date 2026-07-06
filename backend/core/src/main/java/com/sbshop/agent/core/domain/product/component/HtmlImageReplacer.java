package com.sbshop.agent.core.domain.product.component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class HtmlImageReplacer {

	public String replaceImagesBySku(String originalHtml, String sku, List<String> hostedImages) {
		if (originalHtml == null || originalHtml.isEmpty()) {
			return originalHtml;
		}

		String regex = "(?i)<img[^>]*src=[\"'][^\"']*" + Pattern.quote(sku)
				+ "[^\"']*[\"'][^>]*>(?:\\s*<br\\s*/?>\\s*)*";
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
