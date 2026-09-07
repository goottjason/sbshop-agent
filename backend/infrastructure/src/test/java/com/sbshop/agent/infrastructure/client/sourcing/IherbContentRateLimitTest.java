package com.sbshop.agent.infrastructure.client.sourcing;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.content.ProductContentThrottledException;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.sourcing.content.IherbProductContentSource;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

@SuppressWarnings({"rawtypes", "unchecked"})
class IherbContentRateLimitTest {
	private final String url = "https://www.iherb.com/pr/example/12345";
	private final Instant expected = Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.SECONDS);

	HttpClient limited(boolean httpDate) throws Exception {
		var client = mock(HttpClient.class);
		var response = mock(HttpResponse.class);
		String header = httpDate ? DateTimeFormatter.RFC_1123_DATE_TIME.format(expected.atZone(ZoneOffset.UTC)) : "600";
		when(response.statusCode()).thenReturn(429);
		when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of(header)), (a, b) -> true));
		doReturn(response).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		return client;
	}

	void check(ProductContentThrottledException error, boolean httpDate, Instant before) {
		if (httpDate)
			assertThat(error.retryAfter()).isEqualTo(expected);
		else
			assertThat(error.retryAfter()).isBetween(before.plusSeconds(600), Instant.now().plusSeconds(600));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void catalog429PassesServerDelayWithoutImmediateRetry(boolean httpDate) throws Exception {
		var client = new IherbScraperClient(new ObjectMapper());
		var http = limited(httpDate);
		ReflectionTestUtils.setField(client, "httpClient", http);
		Instant before = Instant.now();
		var error = assertThrows(ProductContentThrottledException.class, () -> client.crawlProductContentAsDto(url));
		check(error, httpDate, before);
		verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void image429PassesServerDelayAndNeverUploadsPartialImages(boolean httpDate) throws Exception {
		var crawler = mock(IherbScraperClient.class);
		var storage = mock(ImageStorageClient.class);
		when(crawler.crawlProductContentAsDto(url)).thenReturn(ScrapedProductDto.builder().sourceUrl(url)
			.baseName("Example").vendor(VendorType.IHB)
			.sourceImages(List.of("https://cloudinary.images-iherb.com/image/upload/images/example/l/1.jpg"))
			.rawSourceHtml("<p>Description</p>").build());
		var source = new IherbProductContentSource(crawler, storage);
		var http = limited(httpDate);
		ReflectionTestUtils.setField(source, "http", http);
		Instant before = Instant.now();
		var error = assertThrows(ProductContentThrottledException.class, () -> source.fetch(url));
		check(error, httpDate, before);
		verifyNoInteractions(storage);
		verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}
}
