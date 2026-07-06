package com.sbshop.agent.infrastructure.client.coupang.component;

import com.sbshop.agent.core.domain.product.Product;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CoupangSearchTagGenerator {

	private static final List<String> MAGIC_KEYWORDS = List.of(
			"해외직구", "미국직구", "정품", "가성비", "영양제추천");

	public List<String> generateTags(Product product) {
		Set<String> tags = new LinkedHashSet<>();
		if (product.getBrand() != null) tags.add(cleanText(product.getBrand()));
		if (product.getBaseName() != null) {
			for (String word : product.getBaseName().split("\\s+")) {
				String cleaned = cleanText(word);
				if (cleaned.length() > 1 && cleaned.length() <= 20) tags.add(cleaned);
			}
		}
		tags.addAll(MAGIC_KEYWORDS);
		return tags.stream().limit(20).collect(Collectors.toList());
	}

	private String cleanText(String text) {
		return text.replaceAll("[^a-zA-Z0-9가-힣]", "");
	}
}
