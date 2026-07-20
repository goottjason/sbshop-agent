package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.math.BigDecimal;
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
 * D-092: ElevenstMarketClient.syncImagesAndHtml — buildProductXml 재구성 상품수정 PUT 경로 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientImagesTest {

    @Mock private ElevenstMarketRestClient restClient;
    @Mock private Product product;

    private ElevenstMarketClient client;
    private Map<String, Object> raw;

    @BeforeEach
    void setUp() {
        client = new ElevenstMarketClient(restClient);
        raw = new HashMap<>();
        raw.put("prdNo", "PRD9");
        lenient().when(product.getProductName()).thenReturn("상품명");
        lenient().when(product.getBaseName()).thenReturn("base");
        lenient().when(product.getBrand()).thenReturn("브랜드");
        lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("10000"));
        lenient().when(product.getStock()).thenReturn(99);
        lenient().when(product.getHostedImages()).thenReturn(List.of("u0"));
        lenient().when(product.getDetailHtml()).thenReturn("<p>hi</p>");
    }

    @Test
    @DisplayName("성공 응답 → 상품수정 PUT + 상세HTML CDATA 포함 전문 전송")
    void successCallsProductUpdateWithCdata() {
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<ClientMessage><resultCode>200</resultCode></ClientMessage>");

        client.syncImagesAndHtml(product, "PRD9", raw, List.of("u0"), "<p>hi</p>");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(restClient).put(eq("/rest/prodservices/product/PRD9"), body.capture());
        assertThat(body.getValue()).contains("<![CDATA[<p>hi</p>]]>");
    }

    @Test
    @DisplayName("상품수정 ERROR 응답 → RuntimeException 발생")
    void errorResponseThrowsRuntimeException() {
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<resultCode>ERROR</resultCode><message>실패</message>");

        assertThatThrownBy(() -> client.syncImagesAndHtml(product, "PRD9", raw, List.of("u0"), "<p>hi</p>"))
            .isInstanceOf(RuntimeException.class);
    }
}
