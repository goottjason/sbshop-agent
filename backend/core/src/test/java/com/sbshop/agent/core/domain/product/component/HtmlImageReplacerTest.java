package com.sbshop.agent.core.domain.product.component;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HtmlImageReplacerTest {

	private final HtmlImageReplacer replacer = new HtmlImageReplacer();

	@Test
	@DisplayName("SKU가 포함된 이미지 태그를 새 이미지로 교체한다")
	void replaceImagesBySku_replacesMatchingImageTags() {
		String html = "<div>소개</div><img src=\"https://img.iherb.com/260707IHB001_1.jpg\"><br><br><p>내용</p>";
		List<String> hostedImages = List.of("https://r2.dev/new1.jpg", "https://r2.dev/new2.jpg");

		String result = replacer.replaceImagesBySku(html, "260707IHB001", hostedImages);

		assertThat(result).contains("https://r2.dev/new1.jpg");
		assertThat(result).contains("https://r2.dev/new2.jpg");
		assertThat(result).doesNotContain("https://img.iherb.com/260707IHB001_1.jpg");
	}

	@Test
	@DisplayName("SKU가 포함된 이미지가 여러 개면 첫 번째만 교체하고 나머지는 제거한다")
	void replaceImagesBySku_replacesFirstAndRemovesRest() {
		String html = "<img src=\"https://img.iherb.com/SKU001_a.jpg\"><br><br><img src=\"https://img.iherb.com/SKU001_b.jpg\"><br>";
		List<String> hostedImages = List.of("https://r2.dev/new.jpg");

		String result = replacer.replaceImagesBySku(html, "SKU001", hostedImages);

		assertThat(result).contains("https://r2.dev/new.jpg");
		long count = result.lines().filter(l -> l.contains("r2.dev/new.jpg")).count();
		assertThat(count).isEqualTo(1);
	}

	@Test
	@DisplayName("빈 HTML이면 그대로 반환한다")
	void replaceImagesBySku_emptyHtml_returnsEmpty() {
		String result = replacer.replaceImagesBySku("", "SKU", List.of("url"));
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("null HTML이면 null을 반환한다")
	void replaceImagesBySku_nullHtml_returnsNull() {
		String result = replacer.replaceImagesBySku(null, "SKU", List.of("url"));
		assertThat(result).isNull();
	}
}
