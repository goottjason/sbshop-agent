package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.common.MarketApiEvidence;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class ElevenstReviewedFieldsTest {
	final ElevenstMarketRestClient rest = mock(ElevenstMarketRestClient.class);
	final Product p = mock(Product.class);
	final ElevenstReviewedFields fields = new ElevenstReviewedFields(rest);
	final String path = "/rest/prodmarketservice/prodmarket/123";
	String html = " <p>새 상세</p> ";

	@BeforeEach void setup() {
        when(rest.accountReference()).thenReturn("account");
        when(p.getSbCode()).thenReturn("SB123"); when(p.getDetailHtml()).thenReturn(html);
        current("103", "SB123", "old");
    }

	void current(String state, String sb, String detail) {
        when(rest.requestStrict("GET", path, null)).thenReturn("<Product><prdNo>123</prdNo><sellerPrdCd>" + sb
            + "</sellerPrdCd><selStatCd>" + state + "</selStatCd><htmlDetail><![CDATA[" + detail + "]]></htmlDetail></Product>");
    }

	@Test
	void dedicatedPostPreservesHtmlAndNeverWritesFullProductOrStatus() {
		var review = fields.prepare(p, "123", Set.of("detailHtml"));
		var guard = mock(Runnable.class);
		fields.write("123", "SB123", review, guard);
		var body = ArgumentCaptor.forClass(String.class);
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).requestStrict(eq("POST"), eq("/rest/prodservices/updateProductDetailCont/123"),
			body.capture());
		assertThat(MarketApiEvidence.xml(body.getValue()).getTextContent()).isEqualTo(html);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void actualHtmlReadPreservesWhitespaceAndDoesNotUseRequestFieldName() {
		current("104", "SB123", " \n<p>현재</p> ");
		var read = fields.read("123", "SB123", Set.of("detailHtml"));
		assertThat(read.values()).containsEntry("detailHtml", " \n<p>현재</p> ");
		assertThat(read.writeBlockReason()).isNull();
	}

	@Test
	void stoppedWrongSbMissingHtmlAndUnknownFieldsCannotWrite() {
		current("105", "SB123", "old");
		assertThatThrownBy(() -> fields.prepare(p, "123", Set.of("detailHtml"))).hasMessageContaining("105");
		current("103", "OTHER", "old");
		assertThatThrownBy(() -> fields.prepare(p, "123", Set.of("detailHtml"))).hasMessageContaining("SB코드");
		when(rest.requestStrict("GET", path, null)).thenReturn(
			"<Product><prdNo>123</prdNo><sellerPrdCd>SB123</sellerPrdCd><selStatCd>103</selStatCd></Product>");
		assertThatThrownBy(() -> fields.read("123", "SB123", Set.of("detailHtml"))).hasMessageContaining("실제 상세");
		assertThatThrownBy(() -> fields.prepare(p, "123", Set.of("manufacturer")))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).requestStrict(eq("POST"), any(), any());
	}

	@Test void cdataTerminatorRemainsLiteralAndUnsupportedEncodingIsRejected() {
        when(p.getDetailHtml()).thenReturn("<p>]]></p>");
        var review=fields.prepare(p,"123",Set.of("detailHtml")); fields.write("123","SB123",review,()->{});
        var body=ArgumentCaptor.forClass(String.class);
        verify(rest).requestStrict(eq("POST"),any(),body.capture());
        assertThat(MarketApiEvidence.xml(body.getValue()).getTextContent()).isEqualTo("<p>]]></p>");
        when(p.getDetailHtml()).thenReturn("<p>😀</p>");
        assertThatThrownBy(() -> fields.prepare(p,"123",Set.of("detailHtml"))).hasMessageContaining("EUC-KR");
    }

	@Test
	void finalAccountRecheckAndGuardAbortPreventPost() {
		var review = fields.prepare(p, "123", Set.of("detailHtml"));
		RuntimeException aborted = new IllegalStateException("guard aborted");
		var adapter = new ElevenstMarketClient(rest);
		assertThatThrownBy(() -> adapter.writePreparedProductFields("123", null, "SB123", review, () -> {
			throw aborted;
		})).isSameAs(aborted);
		assertThatThrownBy(
			() -> fields.write("123", "SB123", review, () -> when(rest.accountReference()).thenReturn("other")))
			.hasMessageContaining("계정");
		verify(rest, never()).requestStrict(eq("POST"), any(), any());
	}

	@Test
	void unchangedHtmlDoesNotWriteAndStoppedReadStaysBlocked() {
		var review = fields.prepare(p, "123", Set.of("detailHtml"));
		current("104", "SB123", html);
		fields.write("123", "SB123", review, () -> {
			throw new AssertionError("no write needed");
		});
		current("105", "SB123", html);
		assertThat(fields.read("123", "SB123", Set.of("detailHtml")).writeBlockReason()).contains("105");
		verify(rest, never()).requestStrict(eq("POST"), any(), any());
	}
}
