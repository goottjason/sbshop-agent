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
		// Fake physical exchanges behind the one-shot transport. Its wire-level behavior has separate tests.
		doAnswer(call -> {
			call.<Runnable>getArgument(3).run();
			if (!call.<String>getArgument(2).equals(rest.accountReference()))
				throw new UnsupportedOperationException("11번가 계정이 변경되었습니다.");
			return rest.requestStrict("PUT", call.getArgument(0), call.getArgument(1));
		}).when(rest).mutateStockOnce(any(), any(), any(), any());
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
		assertThat(read.saleState()).isEqualTo("103");
		assertThat(read.stockState()).isEqualTo("01");
	}

	@Test
	void exactOperatingSellingXmlReads997NotDetailZeroOrCumulativeSalesTwo() throws Exception {
		try (var resource = getClass().getResourceAsStream("/elevenst/stock-selling-2026-09-08.json")) {
			assertThat(resource).isNotNull();
			var evidence = new ObjectMapper().readTree(resource);
			String id = evidence.path("prdNo").asText(), sb = evidence.path("sbCode").asText();
			var detail = new HashMap<String, String>();
			evidence.path("observations").get(0).path("fields")
				.forEach(field -> detail.put(field.path("tag").asText(), field.path("value").asText()));
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
	void observedOperatingSoldOutShapeUsesStockQuantityWithoutDisplayRestart() {
		product("104", "123", "SB123");
		stocks(stock("123", "456", "0", "02", "0"), "123", "SB123", "");
		var read = adapter.readStockQuantity("123", null, "SB123");
		assertThat(read.quantity()).isZero();
		assertThat(read.writable()).isTrue();
		assertThat(read.saleState()).isEqualTo("104");
		assertThat(read.stockState()).isEqualTo("02");
		var guard = mock(Runnable.class);
		adapter.writeStockQuantity("123", "456", "SB123", 300, "account", guard);
		verify(guard).run();
		verify(rest).requestStrict(eq("PUT"), eq(writePath), contains("<stckQty>300</stckQty>"));
		verify(rest, never()).requestStrict(eq("PUT"), contains("restartdisplay"), any());
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 1000000})
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
	@ValueSource(strings = {"101", "102", "106", "107", "108", "999"})
	void neverRestartsProhibitedForcedEndedApprovalOrUnknownStates(String state) {
		product(state, "123", "SB123");
		assertThat(adapter.readStockQuantity("123", null, "SB123").writable()).isFalse();
		for (int target : new int[] {0, 300})
			assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", target, "account", () -> {}))
				.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).requestStrict(eq("PUT"), any(), any());
	}

	@Test
	void zeroQuantityIsPreservedIndependentlyAndUnknownInventoryStateCannotEnableWrites() {
		stocks(stock("123", "456", "0", "01", "0"), "123", "SB123", "");
		var read = adapter.readStockQuantity("123", null, "SB123");
		assertThat(read.quantity()).isZero();
		assertThat(read.saleState()).isEqualTo("103");
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
	void zeroUsesOnlyInventoryMutationAndNeverSynthesizesSoldOutFromReceipt() {
		var guard = mock(Runnable.class);
		adapter.writeStockQuantity("123", "456", "SB123", 0, "account", guard);
		verify(guard).run();
		verify(rest).requestStrict(eq("PUT"), eq(writePath), contains("<stckQty>0</stckQty>"));
		// This stale observation must remain 17/103/01 despite a successful zero-quantity receipt.
		var after = adapter.readStockQuantity("123", "456", "SB123");
		assertThat(after.quantity()).isEqualTo(17);
		assertThat(after.saleState()).isEqualTo("103");
		assertThat(after.stockState()).isEqualTo("01");
		verify(rest, never()).requestStrict(eq("PUT"), contains("display"), any());
	}

	@Test
	void aTemporaryDisplayStopDoesNotTurnPositiveRawQuantityIntoZero() {
		product("105", "123", "SB123");
		var before = adapter.readStockQuantity("123", "456", "SB123");
		assertThat(before.quantity()).isEqualTo(17);
		assertThat(before.saleState()).isEqualTo("105");
		adapter.writeStockQuantity("123", "456", "SB123", 0, "account", () -> {});
		verify(rest).requestStrict(eq("PUT"), eq(writePath), contains("<stckQty>0</stckQty>"));
		verify(rest, never()).requestStrict(eq("PUT"), contains("restartdisplay"), any());
	}

	@Test
	void temporaryDisplayRestartRequiresMatchingFreshQuantityAndUsesItsOwnGuard() {
		product("105", "123", "SB123");
		when(rest.requestStrict("PUT", "/rest/prodstatservice/stat/restartdisplay/123", null))
			.thenReturn(
				"<ClientMessage><resultCode>200</resultCode><message>판매상태가 수정되었습니다. [STAT : 103]</message></ClientMessage>");
		var guard = mock(Runnable.class);
		adapter.writeStockQuantity("123", "456", "SB123", 300, "account", guard);
		verify(rest).requestStrict(eq("PUT"), eq(writePath), contains("<stckQty>300</stckQty>"));
		verify(rest, never()).requestStrict(eq("PUT"), contains("restartdisplay"), any());
		stocks(stock("123", "456", "300", "01", "0.125"), "123", "SB123", "");
		adapter.writeStockQuantity("123", "456", "SB123", 300, "account", guard);
		verify(guard, times(2)).run();
		verify(rest).requestStrict("PUT", "/rest/prodstatservice/stat/restartdisplay/123", null);
		var after = adapter.readStockQuantity("123", "456", "SB123");
		assertThat(after.saleState()).isEqualTo("105");
		assertThat(after.quantity()).isEqualTo(300);
	}

	@Test
	void pauseOrAccountChangeBetweenQuantityAndRestartCannotSendSecondMutation() {
		product("105", "123", "SB123");
		adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {});
		stocks(stock("123", "456", "300", "01", "0"), "123", "SB123", "");
		var aborted = new IllegalStateException("batch paused");
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {
			throw aborted;
		})).isSameAs(aborted);
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account",
			() -> when(rest.accountReference()).thenReturn("another account")))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).requestStrict(eq("PUT"), contains("restartdisplay"), any());
	}

	@Test
	void prohibitionObservedAfterQuantityChangePreventsRestart() {
		product("105", "123", "SB123");
		adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {});
		product("108", "123", "SB123");
		stocks(stock("123", "456", "300", "01", "0"), "123", "SB123", "");
		assertThatThrownBy(() -> adapter.writeStockQuantity("123", "456", "SB123", 300, "account", () -> {}))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).requestStrict(eq("PUT"), contains("restartdisplay"), any());
	}

	@Test
	void zeroAlreadyConfirmedWithExplicitStockAndSaleStatesNeedsNoWrite() {
		stocks(stock("123", "456", "0", "02", "0"), "123", "SB123", "");
		for (String state : new String[] {"104", "105"}) {
			product(state, "123", "SB123");
			adapter.writeStockQuantity("123", "456", "SB123", 0, "account", () -> fail("unnecessary write"));
		}
		verify(rest, never()).requestStrict(eq("PUT"), any(), any());
	}

	@Test
	void productSoldOutButInventoryUsableStillNeedsTheZeroMutationAndIndependentReadback() {
		product("104", "123", "SB123");
		stocks(stock("123", "456", "0", "01", "0"), "123", "SB123", "");
		var guard = mock(Runnable.class);
		adapter.writeStockQuantity("123", "456", "SB123", 0, "account", guard);
		verify(guard).run();
		verify(rest).requestStrict(eq("PUT"), eq(writePath), contains("<stckQty>0</stckQty>"));
		assertThat(adapter.readStockQuantity("123", "456", "SB123").stockState()).isEqualTo("01");
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
