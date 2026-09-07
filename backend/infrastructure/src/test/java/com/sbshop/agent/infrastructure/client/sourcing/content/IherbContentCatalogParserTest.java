package com.sbshop.agent.infrastructure.client.sourcing.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.product.content.ProductContentFailureException;
import com.sbshop.agent.core.application.product.content.ProductContentFailureException.Code;
import com.sbshop.agent.infrastructure.client.sourcing.IherbScraperClient;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IherbContentCatalogParserTest {
	private static final String SOURCE = "https://kr.iherb.com/pr/thorne-s-a-t-60-capsules/18566";
	private final ObjectMapper mapper = new ObjectMapper();
	private final IherbContentCatalogParser parser = new IherbContentCatalogParser(mapper);

	private ObjectNode fixture() throws Exception {
		try (var input = getClass().getResourceAsStream("/sourcing/ihb-content-2026-09-07-18566.json")) {
			return (ObjectNode)mapper.readTree(input);
		}
	}

	private void fails(ObjectNode body, String source, Code expected) {
		assertThatThrownBy(() -> parser.parse(body.toString(), source))
			.isInstanceOfSatisfying(ProductContentFailureException.class,
				failure -> assertThat(failure.code()).isEqualTo(expected));
	}

	@Test
	void verifiedLiveShapeWorksWithoutLegacyProductNameOrHtmlDescription() throws Exception {
		var body = fixture();
		assertThat(body.has("productName")).isFalse();
		assertThat(body.has("htmlDescription")).isFalse();
		var dto = parser.parse(body.toString(), SOURCE);
		assertThat(dto.baseName()).isEqualTo(body.path("displayName").textValue());
		assertThat(dto.sourceUrl()).isEqualTo(SOURCE);
		assertThat(dto.sourceImages()).hasSize(3);
		assertThat(dto.sourceImages().getFirst()).endsWith("/thr/thr73202/l/38.jpg");
		String sanitized = IherbProductContentSource.sanitize(dto.rawSourceHtml());
		assertThat(sanitized).contains("상품 설명", "기타 성분", "섭취 방법", "영양 성분 정보", "주의 사항", "참고 안내", "<table")
			.doesNotContain("추가 안내", "<script", "style=");
	}

	@Test
	void primaryImageIsPlacedFirstWithoutTruncatingConfirmedImages() throws Exception {
		var body = fixture();
		body.set("imageIndices", mapper.valueToTree(List.of(43, 46, 38, 47, 48, 49, 50)));
		var dto = parser.parse(body.toString(), SOURCE);
		assertThat(dto.sourceImages()).hasSize(7);
		assertThat(dto.sourceImages()).extracting(url -> url.substring(url.lastIndexOf('/') + 1))
			.containsExactly("38.jpg", "43.jpg", "46.jpg", "47.jpg", "48.jpg", "49.jpg", "50.jpg");
	}

	@Test
	void sourceIdAndCanonicalResponseUrlMustIdentifyTheSameProduct() throws Exception {
		fails(fixture(), "https://kr.iherb.com/pr/other/18567", Code.SOURCE_IDENTITY_MISMATCH);
		var wrongId = fixture();
		wrongId.put("id", 18567);
		fails(wrongId, SOURCE, Code.SOURCE_IDENTITY_MISMATCH);
		var wrongUrl = fixture();
		wrongUrl.put("url", "https://kr.iherb.com/pr/other/18567");
		fails(wrongUrl, SOURCE, Code.SOURCE_IDENTITY_MISMATCH);
		var wrongHost = fixture();
		wrongHost.put("url", "https://kr.iherb.com.evil.example/pr/other/18566");
		fails(wrongHost, SOURCE, Code.SOURCE_IDENTITY_MISMATCH);
		assertThat(parser.parse(fixture().toString(), "https://www.iherb.com/pr/previous-slug/18566").sourceUrl())
			.isEqualTo("https://www.iherb.com/pr/previous-slug/18566");
	}

	@Test
	void missingPrimaryOrWrongIndexTypesAreRejectedWithoutGuessingTheRepresentativeImage() throws Exception {
		var missing = fixture();
		missing.remove("primaryImageIndex");
		fails(missing, SOURCE, Code.SOURCE_IMAGES_INVALID);
		var absent = fixture();
		absent.put("primaryImageIndex", 99);
		fails(absent, SOURCE, Code.SOURCE_IMAGES_INVALID);
		for (String indices : List.of("[38,\"43\"]", "[38,38]", "[38,-1]", "[38,43.2]")) {
			var invalid = fixture();
			invalid.set("imageIndices", mapper.readTree(indices));
			fails(invalid, SOURCE, Code.SOURCE_IMAGES_INVALID);
		}
	}

	@Test
	void missingNameAndMalformedSectionHaveDistinctSafeReasons() throws Exception {
		var noName = fixture();
		noName.remove("displayName");
		noName.put("productName", "legacy field is not a verified substitute");
		fails(noName, SOURCE, Code.SOURCE_NAME_MISSING);
		var malformed = fixture();
		malformed.set("warnings", mapper.createObjectNode().put("secret", "sensitive-response"));
		fails(malformed, SOURCE, Code.SOURCE_DETAILS_INVALID);
		assertThatThrownBy(() -> parser.parse(malformed.toString(), SOURCE))
			.hasMessageNotContaining("sensitive-response");
	}

	@Test
	void actualContentRouteUsesTheVerifiedParserAndLeavesLegacyNameMappingUnchanged() throws Exception {
		var client = new IherbScraperClient(mapper);
		var http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		ReflectionTestUtils.setField(client, "httpClient", http);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn(fixture().toString());
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		assertThat(client.crawlProductContentAsDto(SOURCE).baseName()).isEqualTo("S.A.T.®, 60정");
		assertThat(client.crawlProductInfoAsDto(SOURCE).baseName()).isEmpty();
		verify(http, times(2)).send(
			argThat(request -> request.uri().toString().equals("https://catalog.app.iherb.com/product/18566")),
			any(HttpResponse.BodyHandler.class));
	}

	@Test
	void contentHttpFailureIsReportedWithStatusAndDoesNotInventAProduct() throws Exception {
		var client = new IherbScraperClient(mapper);
		var http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		ReflectionTestUtils.setField(client, "httpClient", http);
		when(response.statusCode()).thenReturn(403);
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		assertThatThrownBy(() -> client.crawlProductContentAsDto(SOURCE))
			.isInstanceOf(ProductContentFailureException.class)
			.hasMessageContaining("SOURCE_HTTP_FAILED", "403");
		verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}
}
