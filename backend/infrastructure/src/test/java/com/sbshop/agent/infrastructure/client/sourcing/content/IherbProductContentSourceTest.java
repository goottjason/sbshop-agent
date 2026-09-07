package com.sbshop.agent.infrastructure.client.sourcing.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.sourcing.IherbScraperClient;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class IherbProductContentSourceTest {
	@Test
	void executableAndRemoteContentIsRemovedWhileDescriptionTablesRemain() {
		String cleaned = IherbProductContentSource.sanitize("<script>alert(1)</script><style>body{display:none}</style>"
			+ "<p onclick='steal()'>설명 <a href='http://localhost'>본문</a><img src='http://localhost/x'></p>"
			+ "<table><tr><td>원료</td><td>100mg</td></tr></table><iframe src='http://localhost'></iframe>");
		assertThat(cleaned).contains("설명", "본문", "<table>", "100mg")
			.doesNotContain("script", "style", "onclick", "http://localhost", "iframe", "<img", "<a ");
		assertThat(IherbProductContentSource.sanitize("x".repeat(200_001))).isNull();
	}

	@Test
	void oversizedImageCancelsBeforeAccumulatingTheRestOfTheResponse() {
		var subscriber = new IherbProductContentSource.LimitedBodySubscriber(4);
		var subscription = mock(java.util.concurrent.Flow.Subscription.class);
		subscriber.onSubscribe(subscription);
		subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
		subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {4, 5})));
		verify(subscription).cancel();
		assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
	}

	@Test
	void emptyImagesAndOverLimitImageListsAreNotReportedAsComplete() {
		var crawler = mock(IherbScraperClient.class);
		var storage = mock(ImageStorageClient.class);
		var source = new IherbProductContentSource(crawler, storage);
		String url = "https://kr.iherb.com/pr/example/123";
		when(crawler.crawlProductContentAsDto(url))
			.thenReturn(ScrapedProductDto.builder().sourceUrl(url).vendor(VendorType.IHB)
				.baseName("name").sourceImages(List.of()).rawSourceHtml("<p>본문</p>").build());
		var empty = source.fetch(url);
		assertThat(empty.imagesComplete()).isFalse();
		assertThat(empty.detailComplete()).isTrue();
		when(crawler.crawlProductContentAsDto(url)).thenReturn(ScrapedProductDto.builder().sourceUrl(url)
			.vendor(VendorType.IHB)
			.baseName("name").sourceImages(java.util.stream.IntStream.range(0, 9).mapToObj(i -> "image" + i).toList())
			.rawSourceHtml("<p>본문</p>").build());
		assertThat(source.fetch(url).imagesComplete()).isFalse();
		verifyNoInteractions(storage);
		verify(crawler, never()).crawlProductInfoAsDto(anyString());
	}
}
