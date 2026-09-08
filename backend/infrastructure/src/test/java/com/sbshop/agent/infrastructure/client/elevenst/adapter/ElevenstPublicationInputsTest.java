package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.domain.market.client.MarketPreparationRequestScope;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class ElevenstPublicationInputsTest {
	final ElevenstMarketRestClient rest = mock(ElevenstMarketRestClient.class);
	final Product product = mock(Product.class);
	final ElevenstPublicationInputs inputs = new ElevenstPublicationInputs(rest);
	static final String OUT = "/rest/areaservice/outboundarea", IN = "/rest/areaservice/inboundarea";
	static final String XML = "<ns2:inOutAddresss><ns2:result_message>SUCCESS</ns2:result_message><ns2:inOutAddress><addrSeq>5</addrSeq><addrNm>선택 주소</addrNm><addr>사용자의 확인 주소</addr><memNo>private-member</memNo><prtblTlphnNo>private-phone</prtblTlphnNo><rcvrNm>private-name</rcvrNm></ns2:inOutAddress></ns2:inOutAddresss>";

	void setup() {
		when(rest.accountReference()).thenReturn("account-A");
		when(rest.requestStrict("GET", OUT, null)).thenReturn(XML);
		when(rest.requestStrict("GET", IN, null)).thenReturn(XML.replace("<addrSeq>5", "<addrSeq>3"));
	}

	@Test
	void exactAuthenticatedAddressGetOnlyReturnsChoicesWithoutContactsOrRawXml() {
		setup();
		var guards = new AtomicInteger();
		try (var scope = MarketPreparationRequestScope.open(MarketType.ELEVEN_STREET, guards::incrementAndGet, retry -> fail("no 429"))) {
			var result = inputs.describe(product, "123");
			assertThat(result.get("addresses").toString()).contains("code=5", "code=3", "선택 주소").doesNotContain("private-member", "private-phone", "private-name", "inOutAddresss");
			assertThat(result).containsEntry("executable", false);
		}
		assertThat(guards).hasValue(2);
		verify(rest).requestStrict("GET", OUT, null);
		verify(rest).requestStrict("GET", IN, null);
		verify(rest, never()).post(any(), any());
		verify(rest, never()).put(any(), any());
	}

	@Test
	void inputReviewRechecksSelectedCodesAgainstCurrentSellerAddressList() {
		setup();
		var fixture = new ElevenstPublicationContractTest();
		fixture.setup();
		var context = fixture.context(fixture.values(), fixture.notices());
		assertThat(inputs.review(fixture.product, context)).containsEntry("inputComplete", true).containsEntry("executable", false);
		when(rest.requestStrict("GET", IN, null)).thenReturn(XML.replace("<addrSeq>5", "<addrSeq>99"));
		var changed = inputs.review(fixture.product, context);
		assertThat(changed).containsEntry("inputComplete", false);
		assertThat(changed.get("issues").toString()).contains("addrSeqIn", "현재 계정");
	}

	@Test
	void observedLiveRowsWithoutResultMarkerAreAcceptedOnlyWhenCompleteAndNonempty() {
		setup();
		String live = XML.replace("<ns2:result_message>SUCCESS</ns2:result_message>", "");
		when(rest.requestStrict("GET", OUT, null)).thenReturn(live);
		assertThat(inputs.describe(product, null).get("addresses").toString()).contains("code=5");
		when(rest.requestStrict("GET", OUT, null)).thenReturn("<ns2:inOutAddresss/>");
		assertThatThrownBy(() -> inputs.describe(product, null)).hasMessageContaining("성공 여부");
		when(rest.requestStrict("GET", OUT, null)).thenReturn(live.replace("</ns2:inOutAddresss>", "<error>denied</error></ns2:inOutAddresss>"));
		assertThatThrownBy(() -> inputs.describe(product, null)).hasMessageContaining("미확인 응답 필드");
		when(rest.requestStrict("GET", OUT, null)).thenReturn(live.replace("<addrSeq>5</addrSeq>", ""));
		assertThatThrownBy(() -> inputs.describe(product, null)).hasMessageContaining("주소 코드");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "<html>ERROR</html>", "<ns2:inOutAddresss><ns2:result_message>FAIL</ns2:result_message></ns2:inOutAddresss>",
		"<ns2:inOutAddresss><ns2:result_message>SUCCESS</ns2:result_message><ns2:result_message>SUCCESS</ns2:result_message></ns2:inOutAddresss>"})
	void errorOrAmbiguousEnvelopesNeverBecomeAddressChoices(String xml) {
		setup();
		when(rest.requestStrict("GET", OUT, null)).thenReturn(xml);
		assertThatThrownBy(() -> inputs.describe(product, null)).isInstanceOf(RuntimeException.class);
		verify(rest, never()).requestStrict("GET", IN, null);
	}

	@Test
	void accountSwitchOrSharedFenceStopsTheNextHttpRequest() {
		setup();
		when(rest.accountReference()).thenReturn("account-A", "account-A", "account-B");
		assertThatThrownBy(() -> inputs.describe(product, null)).hasMessageContaining("계정이 변경");
		verify(rest, never()).requestStrict("GET", IN, null);
		when(rest.accountReference()).thenReturn("account-A");
		var calls = new AtomicInteger();
		try (var scope = MarketPreparationRequestScope.open(MarketType.ELEVEN_STREET, () -> {
			if (calls.incrementAndGet() == 2) throw new MarketPreparationRequestScope.Blocked("late cooldown");
		}, retry -> {})) {
			assertThatThrownBy(() -> inputs.describe(product, null)).hasMessageContaining("late cooldown");
		}
		verify(rest, never()).requestStrict("GET", IN, null);
	}

	@Test
	void http429PropagatesRetryAfterAndCannotReturnPartialAddressMetadata() {
		setup();
		var headers = new HttpHeaders(); headers.set("Retry-After", "600");
		when(rest.requestStrict("GET", IN, null)).thenThrow(new RestClientResponseException("rate", 429, "rate", headers, new byte[0], StandardCharsets.UTF_8));
		var observed = new AtomicReference<Instant>();
		try (var scope = MarketPreparationRequestScope.open(MarketType.ELEVEN_STREET, () -> {}, observed::set)) {
			assertThatThrownBy(() -> inputs.describe(product, null)).isInstanceOf(MarketTransferFailure.class)
				.satisfies(e -> assertThat(((MarketTransferFailure)e).rateLimited()).isTrue());
		}
		assertThat(observed.get()).isAfter(Instant.now().plusSeconds(590));
		assertThat(MarketPreparationRequestScope.active()).isFalse();
	}
}
