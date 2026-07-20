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
 * D-092: 11번가 대표이미지/상세HTML 재게시 테스트.
 * 어떤 11번가 조회 API도 상품수정용 전체 XML을 반환하지 않으므로(신규/셀러상품조회 필드 누락, productinfo -997),
 * 등록 때 쓰는 buildProductXml(Product)로 전체 상품 전문을 재구성해 상품수정 PUT
 * (/rest/prodservices/product/{prdNo})으로 대표이미지+상세HTML을 한 번에 반영한다.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientRepresentativeImageTest {

    @Mock private ElevenstMarketRestClient restClient;
    @Mock private Product product;

    private ElevenstMarketClient client;
    private Map<String, Object> raw;

    @BeforeEach
    void setUp() {
        client = new ElevenstMarketClient(restClient);
        raw = new HashMap<>();
        // product 는 재게시 직전 새 이미지·상세HTML로 갱신된 상태를 흉내낸다.
        lenient().when(product.getProductName()).thenReturn("상품명");
        lenient().when(product.getBaseName()).thenReturn("base");
        lenient().when(product.getBrand()).thenReturn("브랜드");
        lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("10000"));
        lenient().when(product.getStock()).thenReturn(99);
        lenient().when(product.getHostedImages())
            .thenReturn(List.of("http://new/rep.jpg", "http://new/img2.jpg"));
        lenient().when(product.getDetailHtml()).thenReturn("<p>상세</p>");
    }

    @Test
    @DisplayName("상품수정 PUT 전문에 새 prdImage01·htmlDetail 포함(buildProductXml 재구성)")
    void putsRebuiltProductXmlWithNewImageAndDetail() {
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<ClientMessage><resultCode>200</resultCode></ClientMessage>");

        client.syncImagesAndHtml(product, "PRD9", raw, List.of("http://new/rep.jpg"), "<p>상세</p>");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(restClient).put(eq("/rest/prodservices/product/PRD9"), body.capture());
        String sent = body.getValue();
        assertThat(sent).contains("<prdImage01>http://new/rep.jpg</prdImage01>");
        assertThat(sent).contains("<prdImage02>http://new/img2.jpg</prdImage02>");
        assertThat(sent).contains("<![CDATA[<p>상세</p>]]>");
    }

    @Test
    @DisplayName("PUT ERROR 응답 → RuntimeException")
    void throwsWhenPutFails() {
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<resultCode>ERROR</resultCode><message>수정실패</message>");

        assertThatThrownBy(() ->
            client.syncImagesAndHtml(product, "PRD9", raw, List.of("http://new/rep.jpg"), "<p>상세</p>"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("D-092: AuthMessage(-997) 응답 → RuntimeException(가짜성공 차단)")
    void throwsWhenAuthError() {
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<AuthMessage><resultCode>-997</resultCode>"
                + "<resultMessage>등록된 API 정보가 존재하지 않습니다.</resultMessage></AuthMessage>");

        assertThatThrownBy(() ->
            client.syncImagesAndHtml(product, "PRD9", raw, List.of("http://new/rep.jpg"), "<p>상세</p>"))
            .isInstanceOf(RuntimeException.class);
    }
}
