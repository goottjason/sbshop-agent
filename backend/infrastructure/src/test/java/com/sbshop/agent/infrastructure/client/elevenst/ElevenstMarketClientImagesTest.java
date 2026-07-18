package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-C Task 3: ElevenstMarketClient 상세설명수정 API(updateProductDetailCont) 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientImagesTest {

    @Mock private ElevenstMarketRestClient restClient;

    private ElevenstMarketClient client;
    private Map<String, Object> raw;

    @BeforeEach
    void setUp() {
        client = new ElevenstMarketClient(restClient);
        raw = new HashMap<>();
        raw.put("prdNo", "PRD9");
    }

    @Test
    @DisplayName("성공 응답 → post 호출 + CDATA HTML 포함 XML 전송")
    void successCallsUpdateDetailContWithCdata() {
        // 이미지 있음 → 대표이미지 상품수정(GET+PUT) 경유 후 상세HTML 수정
        when(restClient.get(eq("/rest/prodservices/productinfo/PRD9"))).thenReturn("<Product><prdNo>PRD9</prdNo></Product>");
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString())).thenReturn("<message>성공</message>");
        when(restClient.post(eq("/rest/prodservices/updateProductDetailCont/PRD9"),
            contains("prdDescContClob"))).thenReturn("<Product/><message>성공</message>");

        client.syncImagesAndHtml("PRD9", raw, List.of("u0"), "<p>hi</p>");

        verify(restClient).post(eq("/rest/prodservices/updateProductDetailCont/PRD9"),
            contains("<![CDATA[<p>hi</p>]]>"));
    }

    @Test
    @DisplayName("상세HTML 수정 ERROR 응답 → RuntimeException 발생")
    void errorResponseThrowsRuntimeException() {
        // 이미지 없음 → 대표이미지 경로 건너뛰고 상세HTML 수정만 수행(ERROR 응답으로 예외)
        when(restClient.post(eq("/rest/prodservices/updateProductDetailCont/PRD9"),
            contains("prdDescContClob"))).thenReturn("<resultCode>ERROR</resultCode><message>실패</message>");

        assertThatThrownBy(() -> client.syncImagesAndHtml("PRD9", raw, List.of(), "<p>hi</p>"))
            .isInstanceOf(RuntimeException.class);
    }
}
