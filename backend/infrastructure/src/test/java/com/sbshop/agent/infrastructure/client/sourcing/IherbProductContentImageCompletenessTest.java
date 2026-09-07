package com.sbshop.agent.infrastructure.client.sourcing;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class IherbProductContentImageCompletenessTest {
	@Test
	void contentRefreshRetainsAllImagesWhileLegacyRegistrationStillLimitsToFive() {
		var client = new IherbScraperClient(new ObjectMapper());
		String payload = "{\"productName\":\"상품\",\"partNumber\":\"ABC-123\",\"imageIndices\":[1,2,3,4,5,6,7]}";
		assertThat(client.parseProductInfo(payload, "https://kr.iherb.com/pr/example/123").imageLinks()).hasSize(5);
		assertThat(client.parseProductInfo(payload, "https://kr.iherb.com/pr/example/123", false).imageLinks())
			.hasSize(7);
	}

	@Test
	void malformedOrDuplicateContentIndicesAreNotCoercedIntoDifferentImages() {
		var client = new IherbScraperClient(new ObjectMapper());
		for (String indices : java.util.List.of("[1,\"2\"]", "[1,1.2]", "[1,-1]", "[1,2147483648]", "[1,null]",
			"[1,1]")) {
			String payload = "{\"productName\":\"상품\",\"partNumber\":\"ABC-123\",\"imageIndices\":" + indices + "}";
			assertThat(client.parseProductInfo(payload, "https://kr.iherb.com/pr/example/123", false)).isNull();
		}
	}
}
