package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientRepresentativeImageTest {

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient client;
	private Map<String, Object> raw;

	private static final String CURRENT_XML = "<?xml version=\"1.0\" encoding=\"euc-kr\"?>"
		+ "<Product><prdNo>PRD9</prdNo><prdNm>기존상품</prdNm>"
		+ "<prdImage01>http://old/rep.jpg</prdImage01>"
		+ "<htmlDetail><![CDATA[<p>old</p>]]></htmlDetail>"
		+ "<addrSeqOut>99</addrSeqOut><addrSeqIn>88</addrSeqIn>"
		+ "<message>[PRD9] 조회되었습니다.</message></Product>";

	@BeforeEach
	void setUp() {
		client = new ElevenstMarketClient(restClient);
		raw = new HashMap<>();
	}

	@Test
    @DisplayName("전체전문 GET 후 새 prdImage01·htmlDetail 치환 + 주소코드(5/3) 주입해 PUT")
    void roundTripsAndInjectsRequiredFields() {
        when(restClient.get(eq("/rest/prodmarketservice/prodmarket/PRD9"))).thenReturn(CURRENT_XML);
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<ClientMessage><resultCode>200</resultCode></ClientMessage>");

        client.syncImagesAndHtml(null, "PRD9", raw, List.of("http://new/rep.jpg"), "<p>새상세</p>");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(restClient).put(eq("/rest/prodservices/product/PRD9"), body.capture());
        String sent = body.getValue();
        assertThat(sent).contains("<prdImage01><![CDATA[http://new/rep.jpg]]></prdImage01>");
        assertThat(sent).contains("<![CDATA[<p>새상세</p>]]>");
        assertThat(sent).doesNotContain("http://old/rep.jpg");
        assertThat(sent).contains("<addrSeqOut>5</addrSeqOut>");
        assertThat(sent).contains("<addrSeqIn>3</addrSeqIn>");
        assertThat(sent).doesNotContain("<addrSeqOut>99</addrSeqOut>");
        assertThat(sent).doesNotContain("<message>");
    }

	@Test
    @DisplayName("GET 실패(빈 응답) → RuntimeException, PUT 미호출")
    void throwsWhenGetFails() {
        when(restClient.get(eq("/rest/prodmarketservice/prodmarket/PRD9"))).thenReturn("");

        assertThatThrownBy(() ->
            client.syncImagesAndHtml(null, "PRD9", raw, List.of("http://new/rep.jpg"), "<p>x</p>"))
            .isInstanceOf(RuntimeException.class);
        verify(restClient, never()).put(anyString(), anyString());
    }

	@Test
    @DisplayName("PUT 응답이 resultCode 200/210 아니면 RuntimeException(가짜성공 차단)")
    void throwsWhenPutNotSuccess() {
        when(restClient.get(eq("/rest/prodmarketservice/prodmarket/PRD9"))).thenReturn(CURRENT_XML);
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<ClientMessage><resultCode>500</resultCode><message>원재료 유형 코드 필수</message></ClientMessage>");

        assertThatThrownBy(() ->
            client.syncImagesAndHtml(null, "PRD9", raw, List.of("http://new/rep.jpg"), "<p>x</p>"))
            .isInstanceOf(RuntimeException.class);
    }
}
