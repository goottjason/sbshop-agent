package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

/**
 * D-092: ElevenstMarketClient.syncImagesAndHtml — 전체전문 GET 라운드트립 경로 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientImagesTest {

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient client;
	private Map<String, Object> raw;

	private static final String CURRENT_XML = "<?xml version=\"1.0\" encoding=\"euc-kr\"?>"
		+ "<Product><prdNo>PRD9</prdNo>"
		+ "<prdImage01>http://old/rep.jpg</prdImage01>"
		+ "<htmlDetail><![CDATA[<p>old</p>]]></htmlDetail></Product>";

	@BeforeEach
	void setUp() {
		client = new ElevenstMarketClient(restClient);
		raw = new HashMap<>();
		raw.put("prdNo", "PRD9");
	}

	@Test
    @DisplayName("성공(resultCode 200) → 상세HTML CDATA 포함 전문 PUT")
    void successPutsUpdatedXmlWithCdata() {
        when(restClient.get(eq("/rest/prodmarketservice/prodmarket/PRD9"))).thenReturn(CURRENT_XML);
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<ClientMessage><resultCode>200</resultCode></ClientMessage>");

        client.syncImagesAndHtml(null, "PRD9", raw, List.of("u0"), "<p>hi</p>");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(restClient).put(eq("/rest/prodservices/product/PRD9"), body.capture());
        assertThat(body.getValue()).contains("<![CDATA[<p>hi</p>]]>");
    }

	@Test
    @DisplayName("PUT 실패 응답(200/210 아님) → RuntimeException")
    void errorResponseThrowsRuntimeException() {
        when(restClient.get(eq("/rest/prodmarketservice/prodmarket/PRD9"))).thenReturn(CURRENT_XML);
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<ClientMessage><resultCode>500</resultCode></ClientMessage>");

        assertThatThrownBy(() -> client.syncImagesAndHtml(null, "PRD9", raw, List.of("u0"), "<p>hi</p>"))
            .isInstanceOf(RuntimeException.class);
    }
}
