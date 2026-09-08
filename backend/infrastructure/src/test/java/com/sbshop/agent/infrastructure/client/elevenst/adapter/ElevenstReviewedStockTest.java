package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class ElevenstReviewedStockTest {
	final ElevenstMarketRestClient rest = mock(ElevenstMarketRestClient.class);
	final ElevenstMarketClient adapter = new ElevenstMarketClient(rest);
	final String productPath = "/rest/prodmarketservice/prodmarket/123";
	final String stockPath = "/rest/prodmarketservice/prodmarket/stck/123";
	final String writePath = "/rest/prodservices/stockqty/456";

	@BeforeEach
	void setup() {
		when(rest.accountReference()).thenReturn("account");
		product("103", "123", "SB123");
		stocks(stock("123", "456", "17", "01", "0.125"), "123", "SB123", "");
		when(rest.requestStrict(eq("PUT"), eq(writePath), any())).thenReturn(receipt("200", "456"));
	}

	void product(String state, String id, String sb) {
		when(rest.requestStrict("GET", productPath, null)).thenReturn("<Product><prdNo>" + id
			+ "</prdNo><sellerPrdCd>" + sb + "</sellerPrdCd><selStatCd>" + state
			+ "</selStatCd><prdStckQty>999</prdStckQty></Product>");
	}

	String stock(String id, String stockId, String quantity, String state, String weight) {
		return "<ns2:ProductStock><prdNo>" + id + "</prdNo><prdStckNo>" + stockId
			+ "</prdStckNo><stckQty>" + quantity + "</stckQty><selQty>812</selQty><prdStckStatCd>" + state
			+ "</prdStckStatCd><optWght>" + weight + "</optWght><mixOptNo>0</mixOptNo></ns2:ProductStock>";
	}

	void stocks(String items, String id, String sb, String extra) {
		when(rest.requestStrict("GET", stockPath, null)).thenReturn("<ns2:ProductStocks xmlns:ns2=\"urn:11st\">"
			+ items + "<ns2:prdNo>" + id + "</ns2:prdNo><ns2:sellerPrdCd>" + sb
			+ "</ns2:sellerPrdCd>" + extra + "</ns2:ProductStocks>");
	}

	String receipt(String code, String stockId) {
		return "<ClientMessage><resultCode>" + code + "</resultCode><productNo>" + stockId
			+ "</productNo><message>수량 처리 결과</message></ClientMessage>";
	}

	@Test
	void readsExactInventoryQuantityNotCumulativeSalesOrProductTotal() {
		var read = adapter.readStockQuantity("123", null, "SB123");
		assertThat(read.quantity()).isEqualTo(17);
		assertThat(read.optionId()).isEqualTo("456");
		assertThat(read.accountReference()).isEqualTo("account");
		assertThat(read.writable()).isTrue();
	}

	@Test
	void exactOperatingSellingXmlReads997NotDetailZeroOrCumulativeSalesTwo() throws Exception {
		try (var resource = getClass().getResourceAsStream("/elevenst/stock-selling-2026-09-08.json")) {
			assertThat(resource).isNotNull();
			var evidence = new ObjectMapper().readTree(resource);
			String id = evidence.path("prdNo").asText(), sb = evidence.path("sbCode").asText();
			var detail = new HashMap<String, String>();
			evidence.path("observations").get(0).path("fields").forEach(field ->
				detail.put(field.path("tag").asText(), field.path("value").asText()));
			assertThat(detail).containsEntry("selStatCd", "103").containsEntry("prdStckQty", "0");
			// Only the separately captured identity/state fields reconstruct this minimal detail response.
			String productXml = "<Product><prdNo>" + detail.get("prdNo") + "</prdNo><sellerPrdCd>"
				+ detail.get("sellerPrdCd") + "</sellerPrdCd><selStatCd>" + detail.get("selStatCd")
				+ "</selStatCd><prdStckQty>" + detail.get("prdStckQty") + "</prdStckQty></Product>";
			var inventory = evidence.path("observations").get(1);
			String exactStockXml = inventory.path("stockXml").asText();
			assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(exactStockXml.getBytes(Charset.forName("EUC-KR")))))
				.isEqualTo(inventory.path("sha256").asText());
			assertThat(exactStockXml).contains("<selQty>2</selQty>", "<optWght>0</optWght>");
			when(rest.requestStrict("GET", "/rest/prodmarketservice/prodmarket/" + id, null)).thenReturn(productXml);
			when(rest.requestStrict("GET", "/rest/prodmarketservice/prodmarket/stck/" + id, null))
				.thenReturn(exactStockXml);
			var read = adapter.readStockQuantity(id, null, sb);
			assertThat(read.quantity()).isEqualTo(997);
			assertThat(read.optionId()).isEqualTo("16243384893");
			assertThat(read.writable()).isTrue();
			assertThat(read.accountReference()).isEqualTo("account");
			assertThat(evidence.path("externalWrites").asInt()).isZero();
			verify(rest, never()).requestStrict(eq("PUT"), any(), any());
			verify(rest, never()).requestStrict(eq("POST"), any(), any());
		}
	}

	@Test
	void writesOnlyDedicatedFourRequiredFieldsAndPreservesFreshWeight() {
		var guard = mock(Runnable.class);
		adapter.writeStockQuantity("123", "456", "SB123", 300, "account", guard);
		var body = ArgumentCaptor.forClass(String.class);
		var order = inOrder(rest, guard);
		order.verify(rest).requestStrict("GET", productPath, null);
		order.verify(rest).requestStrict("GET", stockPath, null);
		order.verify(guard).run();
		order.verify(rest).requestStrict(eq("PUT"), eq(writePath), body.capture());
		var root = MarketApiEvidence.xml(body.getValue());
		assertThat(root.getNodeName()).isEqualTo("ProductStock");
		assertThat(root.getChildNodes().getLength()).isEqualTo(4);
		assertThat(MarketApiEvidence.text(root, "prdNo")).isEqualTo("123");
		assertThat(MarketApiEvidence.text(root, "prdStckNo")).isEqualTo("456");
		assertThat(MarketApiEvidence.text(root, "stckQty")).isEqualTo("300");
		assertThat(MarketApiEvidence.text(root, "optWght")).isEqualTo("0.125");
		verify(rest, never()).put(any(), any());
		verify(rest, never()).requestStrict(eq("POST"), any(), any());
	}

	@Test
	void observedOperatingSoldOutShapeStaysBlockedWithoutRestart() {
		product("104", "123", "SB123");
		stocks(stock("123", "456", "0", "02", "0"), "123", "SB123", "");
		var read = adapter.readStockQuantity("123", null, "SB123");
		assertThat(read.quantity()).isZero();
		assertThat(read.writable()).isFalse();
		assertThat(read.reason()).contains("104", "02", "재개");
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {}))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).requestStrict(eq("PUT"), any(), any());
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 1000000})
	void rejectsUnsupportedTargetQuantityBeforeAnyRemoteCall(int quantity) {
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", quantity, "account", () -> {}))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).requestStrict(any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "-1", "2.5", "unknown", "100000000"})
	void missingOrInvalidStockDoesNotFallBackToSalesCount(String quantity) {
		stocks(stock("123", "456", quantity, "01", "0"), "123", "SB123", "");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", null, "SB123"))
			.isInstanceOf(MarketTransferFailure.class).hasMessageContaining("정수");
	}

	@Test
	void rejectsDifferentSbProductAndStockAtEveryIdentityBoundary() {
		product("103", "123", "OTHER");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", null, "SB123")).hasMessageContaining("SB코드");
		verify(rest, never()).requestStrict("GET", stockPath, null);
		product("103", "124", "SB123");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", null, "SB123")).hasMessageContaining("상품번호");
		product("103", "123", "SB123");
		stocks(stock("123", "456", "17", "01", "0"), "123", "OTHER", "");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", null, "SB123")).hasMessageContaining("SB코드");
		stocks(stock("124", "456", "17", "01", "0"), "123", "SB123", "");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", null, "SB123")).hasMessageContaining("상품·재고번호");
		stocks(stock("123", "457", "17", "01", "0"), "123", "SB123", "");
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {}))
			.hasMessageContaining("재고번호");
		verify(rest, never()).requestStrict(eq("PUT"), any(), any());
	}

	@Test
	void neverChoosesFirstOfMultipleInventoryItemsOrAdditionalComponents() {
		String one = stock("123", "456", "17", "01", "0");
		stocks(one + stock("123", "457", "20", "01", "0"), "123", "SB123", "");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", "456", "SB123"))
			.isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("1개");
		stocks(one, "123", "SB123", "<productComponents><productComponent/></productComponents>");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", null, "SB123"))
			.isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("추가구성상품");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "NaN", "-1", "1kg"})
	void unknownWeightIsNotReplacedWithZero(String weight) {
		stocks(stock("123", "456", "17", "01", weight), "123", "SB123", "");
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {}))
			.isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("추가무게");
		verify(rest, never()).requestStrict(eq("PUT"), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"104", "105", "107", "999"})
	void neverRestartsNonSellingStates(String state) {
		product(state, "123", "SB123");
		assertThat(adapter.readStockQuantity("123", null, "SB123").writable()).isFalse();
	}

	@Test
	void zeroObservedQuantityAndUnknownInventoryStateCannotEnableWrites() {
		stocks(stock("123", "456", "0", "01", "0"), "123", "SB123", "");
		assertThat(adapter.readStockQuantity("123", null, "SB123").writable()).isFalse();
		stocks(stock("123", "456", "17", "99", "0"), "123", "SB123", "");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", null, "SB123")).hasMessageContaining("재고 상태");
	}

	@ParameterizedTest
	@ValueSource(strings = {"400", "404", "500", "-1000"})
	void preservesDocumentedBusinessFailureCodeInsteadOfTreatingHttp200AsSuccess(String code) {
		when(rest.requestStrict(eq("PUT"), eq(writePath), any())).thenReturn(receipt(code, "456"));
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {}))
			.isInstanceOfSatisfying(MarketTransferFailure.class, error -> assertThat(error.getCode())
				.isEqualTo("ELEVENST_BUSINESS_" + code));
	}

	@Test
	void receiptProductNoIsInventoryIdNotListingIdAndDuplicateFieldsAreInvalid() {
		when(rest.requestStrict(eq("PUT"), eq(writePath), any())).thenReturn(receipt("200", "123"));
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {}))
			.hasMessageContaining("재고번호");
		stocks(stock("123", "456", "17", "01", "0"), "123", "SB123", "<sellerPrdCd>OTHER</sellerPrdCd>");
		assertThatThrownBy(() -> adapter.readStockQuantity("123", null, "SB123"))
			.isInstanceOf(MarketTransferFailure.class);
	}

	@Test
	void accountChangesOrDurableGuardAbortCannotWriteAndGuardExceptionEscapesUnchanged() {
		RuntimeException aborted = new IllegalStateException("durable lease changed");
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {
			throw aborted;
		})).isSameAs(aborted);
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account",
			() -> when(rest.accountReference()).thenReturn("different")))
			.isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("계정");
		verify(rest, never()).requestStrict(eq("PUT"), any(), any());
	}

	@Test
	void matchingFreshQuantityDoesNotCreateAnotherWriteIntent() {
		AtomicInteger writes = new AtomicInteger();
		adapter.writeStockQuantity("123", "456", "SB123", 17, "account", writes::incrementAndGet);
		assertThat(writes).hasValue(0);
		verify(rest, never()).requestStrict(eq("PUT"), any(), any());
	}

	@Test
	void retryAfterSurvivesHttpFailure() {
		var headers = new HttpHeaders();
		headers.add("Retry-After", "600");
		when(rest.requestStrict(eq("PUT"), eq(writePath), any())).thenThrow(new RestClientResponseException(
			"too many", 429, "", headers, new byte[0], Charset.forName("EUC-KR")));
		Instant before = Instant.now();
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {}))
			.isInstanceOfSatisfying(MarketTransferFailure.class, error -> {
				assertThat(error.rateLimited()).isTrue();
				assertThat(error.getRetryAfter()).isAfterOrEqualTo(before.plusSeconds(599));
			});
	}
}
