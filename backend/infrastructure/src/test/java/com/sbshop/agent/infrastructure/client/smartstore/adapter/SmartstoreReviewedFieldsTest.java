package com.sbshop.agent.infrastructure.client.smartstore.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.market.client.dto.PreparedMarketFields;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class SmartstoreReviewedFieldsTest {
	final ObjectMapper mapper = new ObjectMapper();
	final SmartstoreRestClient rest = mock(SmartstoreRestClient.class);
	final Product product = mock(Product.class);
	SmartstoreReviewedFields fields;
	ObjectNode current;
	final String path = "/v2/products/origin-products/123";

	@BeforeEach void setup() {
        when(rest.accountReference()).thenReturn("account-A");
        when(rest.put(anyString(),any())).thenReturn("{}");
        when(product.getSbCode()).thenReturn("SB-123");when(product.getProductName()).thenReturn("새 이름");
        when(product.getDetailHtml()).thenReturn("<p>새 상세</p>");
        fields=new SmartstoreReviewedFields(rest,mapper,urls->List.of("https://shop-phinf.pstatic.net/new.jpg"));
        current=mapper.createObjectNode().put("name","기존").put("detailContent","<p>기존</p>")
            .put("salePrice",12300).put("stockQuantity",7).put("statusType","SALE");
        current.withObject("/detailAttribute/sellerCodeInfo").put("sellerManagementCode","SB-123");
        publish();
    }

	void publish(){when(rest.get(path)).thenReturn(mapper.createObjectNode().set("originProduct",current).toString());}

	@Test
	void freshMergePreservesOtherEditorsPriceQuantityAndUnknownAttributes() {
		var prepared = fields.prepare(product, "123", null, Set.of("name", "detailHtml"));
		current.put("salePrice", 17900).put("stockQuantity", 4).put("unknownFutureAttribute", "keep");
		publish();
		var guard = mock(Runnable.class);
		fields.write("123", null, "SB-123", prepared, guard);
		var body = ArgumentCaptor.forClass(Map.class);
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).put(eq(path), body.capture());
		var actual = mapper.valueToTree(body.getValue()).path("originProduct");
		assertThat(actual.path("name").asText()).isEqualTo("새 이름");
		assertThat(actual.path("detailContent").asText()).isEqualTo("<p>새 상세</p>");
		assertThat(actual.path("salePrice").intValue()).isEqualTo(17900);
		assertThat(actual.path("stockQuantity").intValue()).isEqualTo(4);
		assertThat(actual.path("unknownFutureAttribute").asText()).isEqualTo("keep");
	}

	@Test
	void wrongSbAndDifferentReturnedOriginNeverWrite() {
		current.withObject("/detailAttribute/sellerCodeInfo").put("sellerManagementCode", "other");
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", null, Set.of("name")))
			.isInstanceOf(IllegalStateException.class);
		current.withObject("/detailAttribute/sellerCodeInfo").put("sellerManagementCode", "SB-123");
		current.put("id", 456);
		publish();
		assertThatThrownBy(() -> fields.read("123", null, "SB-123", Set.of("name")))
			.isInstanceOf(IllegalStateException.class);
		verify(rest, never()).put(any(), any());
	}

	@Test void partialImageHostingNeverProducesReadyPayload() {
        when(product.getHostedImages()).thenReturn(List.of("https://example.com/a.jpg","https://example.com/b.jpg"));
        assertThatThrownBy(()->fields.prepare(product,"123",null,Set.of("hostedImages"))).hasMessageContaining("이미지 전체");
        verify(rest,never()).put(any(),any());
    }

	@Test
	void zeroStockoutPreservesZeroButStoppedListingCannotBeResumed() {
		current.put("statusType", "OUTOFSTOCK").put("stockQuantity", 0);
		publish();
		var prepared = fields.prepare(product, "123", null, Set.of("name"));
		fields.write("123", null, "SB-123", prepared, () -> {});
		var body = ArgumentCaptor.forClass(Map.class);
		verify(rest).put(eq(path), body.capture());
		assertThat(mapper.valueToTree(body.getValue()).path("originProduct").path("stockQuantity").intValue()).isZero();
		clearInvocations(rest);
		current.put("statusType", "SUSPENSION");
		publish();
		assertThatThrownBy(() -> fields.write("123", null, "SB-123", prepared, () -> {})).hasMessageContaining("판매 상태");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void stockoutWithLiveStandardOptionIsNotConvertedToSale() {
		current.put("statusType", "OUTOFSTOCK").put("stockQuantity", 0);
		current.withObject("/detailAttribute/optionInfo").putArray("optionStandards").addObject().put("stockQuantity",
			3);
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", null, Set.of("name"))).hasMessageContaining("품절 옵션");
	}

	@Test
	void guardAccountChangePreventsWriteAndEditedPayloadIsRejected() {
		var prepared = fields.prepare(product, "123", null, Set.of("name"));
		assertThatThrownBy(() -> fields.write("123", null, "SB-123", prepared,
			() -> when(rest.accountReference()).thenReturn("account-B")))
			.hasMessageContaining("계정이 변경");
		when(rest.accountReference()).thenReturn("account-A");
		var forged = new PreparedMarketFields("account-A", "ORIGIN:123", false, prepared.expectedValues(),
			"{\"name\":\"unreviewed\"}");
		assertThatThrownBy(() -> fields.write("123", null, "SB-123", forged, () -> {})).hasMessageContaining("목표값이 바뀌");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void requiredMissingFieldsAndBadImageShapeAreNeverSuccessfulEmptyValues() {
		current.remove("detailContent");
		publish();
		assertThatThrownBy(() -> fields.read("123", null, "SB-123", Set.of("detailHtml")))
			.hasMessageContaining("필수 마켓 필드");
		current.withObject("/images/representativeImage").put("url", "https://example.com/first.jpg");
		current.withObject("/images").put("optionalImages", "wrong");
		publish();
		assertThatThrownBy(() -> fields.read("123", null, "SB-123", Set.of("hostedImages")))
			.hasMessageContaining("추가 이미지 목록");
	}

	@Test
	void channelSpecificNameCannotBeSilentlyLeftDifferent() {
		var response = mapper.createObjectNode();
		response.set("originProduct", current);
		response.withObject("/smartstoreChannelProduct").put("channelProductName", "별도 표시명");
		when(rest.get(path)).thenReturn(response.toString());
		assertThatThrownBy(() -> fields.prepare(product, "123", null, Set.of("name")))
			.hasMessageContaining("채널 전용 상품명");
		assertThat(fields.read("123", null, "SB-123", Set.of("detailHtml")).values()).containsEntry("detailHtml",
			"<p>기존</p>");
		verify(rest, never()).put(any(), any());
	}

	private SmartstoreMarketClient adapter() {
		return new SmartstoreMarketClient(null, null, null, null, rest, mapper);
	}

	@ParameterizedTest
	@ValueSource(strings = {"WAIT", "UNADMISSION", "REJECTION", "SUSPENSION", "CLOSE", "PROHIBITION", "DELETE"})
	void explicitStoppedStatesRemainNonRetryableThroughAdapterAndNeverReachWriteIntent(String state) {
		var adapter = adapter();
		var prepared = adapter.prepareProductFields(product, "123", null, Set.of("detailHtml"));
		current.put("statusType", state);
		publish();
		assertThatThrownBy(() -> adapter.prepareProductFields(product, "123", null, Set.of("detailHtml")))
			.isExactlyInstanceOf(UnsupportedOperationException.class).hasMessageContaining(state);
		var read = adapter.readProductFields("123", null, "SB-123", Set.of("detailHtml"));
		assertThat(read.values()).containsEntry("detailHtml", "<p>기존</p>");
		assertThat(read.writeBlockReason()).contains(state);
		var guard = mock(Runnable.class);
		assertThatThrownBy(() -> adapter.writePreparedProductFields("123", null, "SB-123", prepared, guard))
			.isExactlyInstanceOf(UnsupportedOperationException.class).hasMessageContaining(state);
		verifyNoInteractions(guard);
		verify(rest, never()).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "\"0\"", "0.0", "1", "-1"})
	void unverifiedStockoutQuantityIsBlockedWithoutTreatingTheProductAsAvailable(String quantity) throws Exception {
		current.put("statusType", "OUTOFSTOCK");
		current.set("stockQuantity", mapper.readTree(quantity));
		publish();
		assertThatThrownBy(() -> adapter().prepareProductFields(product, "123", null, Set.of("detailHtml")))
			.isExactlyInstanceOf(UnsupportedOperationException.class).hasMessageContaining("0개 재고");
		assertThat(adapter().readProductFields("123", null, "SB-123", Set.of("detailHtml")).writeBlockReason())
			.contains("0개 재고");
		verify(rest, never()).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"optionCombinations", "optionStandards"})
	void stockoutOptionWithUnknownQuantityRemainsNonRetryableThroughAdapter(String option) {
		current.put("statusType", "OUTOFSTOCK").put("stockQuantity", 0);
		current.withObject("/detailAttribute/optionInfo").putArray(option).addObject().putNull("stockQuantity");
		publish();
		assertThatThrownBy(() -> adapter().prepareProductFields(product, "123", null, Set.of("detailHtml")))
			.isExactlyInstanceOf(UnsupportedOperationException.class).hasMessageContaining("품절 옵션");
		assertThat(adapter().readProductFields("123", null, "SB-123", Set.of("detailHtml")).writeBlockReason())
			.contains("품절 옵션");
		verify(rest, never()).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "FUTURE_UNKNOWN_STATE"})
	void missingOrUnknownStatusRetainsUncertainResponseClassification(String status) {
		current.put("statusType", status);
		if (status.isEmpty())
			current.remove("statusType");
		publish();
		assertThatThrownBy(() -> adapter().prepareProductFields(product, "123", null, Set.of("detailHtml")))
			.isInstanceOfSatisfying(MarketTransferFailure.class,
				failure -> assertThat(failure.getCode()).isEqualTo("INVALID_RESPONSE"));
		verify(rest, never()).put(any(), any());
	}

	@Test
	void transportAndServerFailuresRemainRetryableAnd429RetainsRetryAfter() {
		var adapter = adapter();
		when(rest.get(path)).thenThrow(new ResourceAccessException("timeout"));
		assertThatThrownBy(() -> adapter.prepareProductFields(product, "123", null, Set.of("detailHtml")))
			.isInstanceOfSatisfying(MarketTransferFailure.class,
				failure -> assertThat(failure.getCode()).isEqualTo("TRANSPORT_ERROR"));
		doThrow(new HttpServerErrorException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(rest)
			.get(path);
		assertThatThrownBy(() -> adapter.prepareProductFields(product, "123", null, Set.of("detailHtml")))
			.isInstanceOfSatisfying(MarketTransferFailure.class,
				failure -> assertThat(failure.getCode()).isEqualTo("HTTP_503"));
		var headers = new HttpHeaders();
		headers.set("Retry-After", "120");
		doThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "rate limited",
			headers,
			new byte[0], java.nio.charset.StandardCharsets.UTF_8)).when(rest).get(path);
		var before = java.time.Instant.now();
		assertThatThrownBy(() -> adapter.prepareProductFields(product, "123", null, Set.of("detailHtml")))
			.isInstanceOfSatisfying(MarketTransferFailure.class, failure -> {
				assertThat(failure.getCode()).isEqualTo("HTTP_429");
				assertThat(failure.getRetryAfter()).isAfter(before.plusSeconds(100));
			});
		verify(rest, never()).put(any(), any());
	}

}
