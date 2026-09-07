package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketPresence;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class SmartstoreMarketPresenceTest {
	@Test
	void rateLimitCarriesRetryAfterAndTimeoutIsRetryable() {
		var headers = new HttpHeaders();
		headers.set("Retry-After", "120");
		var before = java.time.Instant.now();
		var failure = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "fixture", headers,
			"{\"code\":\"GW.RATE_LIMIT\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		when(rest.get(PATH)).thenThrow(new RuntimeException("wrapped", failure));
		var result = client.inspectListing(ID);
		assertThat(result.retryable()).isTrue();
		assertThat(result.rateLimited()).isTrue();
		assertThat(result.retryAfter()).isAfterOrEqualTo(before.plusSeconds(120));
		doThrow(new org.springframework.web.client.ResourceAccessException("fixture timeout")).when(rest).get(PATH);
		assertThat(client.inspectListing(ID).retryable()).isTrue();
	}

	@org.junit.jupiter.api.BeforeEach
	void account() {
		lenient().when(rest.accountReference()).thenReturn("fixture-account");
	}

	private final SmartstoreRestClient rest = mock(SmartstoreRestClient.class);
	private final SmartstoreMarketClient client = new SmartstoreMarketClient(null, null, null, null, rest,
		new ObjectMapper());
	private static final String ID = "1234567890";
	private static final String PATH = "/v2/products/origin-products/" + ID;

	private RuntimeException error(HttpStatus status, String code) {
		var cause = HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
			("{\"code\":\"" + code + "\",\"message\":\"fixture\"}").getBytes(StandardCharsets.UTF_8),
			StandardCharsets.UTF_8);
		return new RuntimeException("Smartstore API 호출 실패", cause);
	}

	@Test
    void documentedProductNotFoundIsAbsence() {
        when(rest.get(PATH)).thenThrow(error(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        assertThat(client.checkPresence(ID)).isEqualTo(MarketPresence.ABSENT);
    }

	@Test
	void gatewayNotFoundIsNotProductDeletion() {
        when(rest.get(PATH)).thenThrow(error(HttpStatus.NOT_FOUND, "GW.NOT_FOUND"));
        assertThat(client.checkPresence(ID)).isEqualTo(MarketPresence.UNKNOWN);
	}

	@Test
	void prohibitionEvidenceIsSeparateFromAbsenceAndBoundToAccount() {
		when(rest.get(PATH)).thenReturn("{\"originProduct\":{\"name\":\"상품\",\"statusType\":\"PROHIBITION\"}}");
		var observation = client.inspectListing(ID);
		assertThat(observation.state()).isEqualTo(com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.PROHIBITED);
		assertThat(observation.accountReference()).isEqualTo("fixture-account");
		assertThat(observation.endpoint()).isEqualTo("GET " + PATH);
		when(rest.accountReference()).thenReturn("account-one", "account-two");
		assertThat(client.inspectListing(ID).state()).isEqualTo(com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.UNKNOWN);
	}

	@ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void otherHttpStatusesNeverDetach(int status) {
        when(rest.get(PATH)).thenThrow(error(HttpStatus.valueOf(status), "NOT_FOUND"));
        assertThat(client.checkPresence(ID)).isEqualTo(MarketPresence.UNKNOWN);
    }

	@ParameterizedTest
    @ValueSource(strings = {"SALE", "OUTOFSTOCK", "SUSPENSION", "PROHIBITION", "CLOSE", "WAIT", "UNADMISSION", "REJECTION"})
    void existingStatesAreNotDeletion(String state) {
        when(rest.get(PATH)).thenReturn("{\"originProduct\":{\"id\":1234567890,\"name\":\"상품\",\"statusType\":\"" + state + "\"}}");
        assertThat(client.checkPresence(ID)).isEqualTo(MarketPresence.PRESENT);
    }

	@Test
    void explicitDeleteIsAbsence() {
        when(rest.get(PATH)).thenReturn("{\"originProduct\":{\"name\":\"상품\",\"statusType\":\"DELETE\"}}");
        assertThat(client.checkPresence(ID)).isEqualTo(MarketPresence.ABSENT);
    }

	@ParameterizedTest
    @ValueSource(strings = {"{}", "null", "", "not-json", "{\"originProduct\":null}", "{\"originProduct\":{}}",
        "{\"code\":\"NOT_FOUND\"}", "{\"originProduct\":{\"name\":\"상품\",\"statusType\":\"FUTURE_STATUS\"}}",
        "{\"originProduct\":{\"id\":999,\"name\":\"다른 상품\",\"statusType\":\"DELETE\"}}"})
    void malformedOrUnrelatedResponseIsUnknown(String body) {
        when(rest.get(PATH)).thenReturn(body);
        assertThat(client.checkPresence(ID)).isEqualTo(MarketPresence.UNKNOWN);
    }

	@Test
    void genericErrorAndMissingIdentifierAreUnknown() {
        when(rest.get(PATH)).thenThrow(new RuntimeException("404 Not Found"));
        assertThat(client.checkPresence(ID)).isEqualTo(MarketPresence.UNKNOWN);
        assertThat(client.checkPresence(null)).isEqualTo(MarketPresence.UNKNOWN);
        assertThat(client.checkPresence("../search")).isEqualTo(MarketPresence.UNKNOWN);
        verify(rest, times(1)).get(PATH);
		verify(rest, never()).get("/v2/products/origin-products/../search");
    }
}
