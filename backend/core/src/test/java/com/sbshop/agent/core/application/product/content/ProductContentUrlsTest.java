package com.sbshop.agent.core.application.product.content;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductContentUrlsTest {
	@Test
	void sourceHostAndProductPathAreBothRequired() {
		assertThat(ProductContentUrls.source("https://kr.iherb.com/pr/example/123?rcode=ABC")).contains("/123");
		for (String value : List.of("http://kr.iherb.com/pr/example/123", "https://localhost/product/123",
			"https://kr.iherb.com.evil.example/pr/example/123", "https://user@kr.iherb.com/pr/example/123",
			"https://kr.iherb.com:443/pr/example/123", "https://kr.iherb.com/pr/example/123/extra",
			"https://kr.iherb.com/pr/example/123#fragment", "https://kr.iherb.com/pr/example/123\" onerror=\"1"))
			assertThatThrownBy(() -> ProductContentUrls.source(value)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void imageTransportAcceptsOnlyTheKnownSourceImageHost() {
		assertThat(ProductContentUrls.sourceImage("https://cloudinary.images-iherb.com/image/upload/images/1.jpg"))
			.endsWith("1.jpg");
		for (String value : List.of("https://127.0.0.1/image/upload/1.jpg", "https://iherb.com/image/upload/1.jpg",
			"https://cloudinary.images-iherb.com/redirect?url=http://localhost",
			"https://cloudinary.images-iherb.com@localhost/image/upload/1.jpg"))
			assertThatThrownBy(() -> ProductContentUrls.sourceImage(value))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
