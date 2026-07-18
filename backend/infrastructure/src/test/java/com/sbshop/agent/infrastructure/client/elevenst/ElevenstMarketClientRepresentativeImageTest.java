package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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

/**
 * ElevenstMarketClient 대표이미지(prdImage01) 수정 테스트.
 * syncImagesAndHtml 가 상품수정(PUT /rest/prodservices/product/{prdNo}) 전문에
 * 새 호스팅 이미지 URL로 prdImage01 을 세팅해 전송하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientRepresentativeImageTest {

    @Mock private ElevenstMarketRestClient restClient;

    private ElevenstMarketClient client;
    private Map<String, Object> raw;

    // productinfo GET 이 돌려주는 현재 상품 전문(대표이미지 포함, 필수필드 다수 존재).
    private static final String CURRENT_XML = "<?xml version=\"1.0\" encoding=\"euc-kr\"?>"
        + "<Product>"
        + "<prdNo>PRD9</prdNo>"
        + "<prdNm>기존상품명</prdNm>"
        + "<selPrc>10000</selPrc>"
        + "<prdImage01>http://old/img1.jpg</prdImage01>"
        + "<prdImage02>http://old/img2.jpg</prdImage02>"
        + "<dlvSendCloseTmpltNo>682132</dlvSendCloseTmpltNo>"
        + "</Product>";

    @BeforeEach
    void setUp() {
        client = new ElevenstMarketClient(restClient);
        raw = new HashMap<>();
        raw.put("prdNo", "PRD9");
    }

    @Test
    @DisplayName("hostedImages 있음 → productinfo GET 후 PUT 전문에 새 prdImage01 세팅")
    void updatesRepresentativeImageViaFullModify() {
        when(restClient.get(eq("/rest/prodservices/productinfo/PRD9"))).thenReturn(CURRENT_XML);
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString())).thenReturn("<message>성공</message>");
        when(restClient.post(eq("/rest/prodservices/updateProductDetailCont/PRD9"), contains("prdDescContClob")))
            .thenReturn("<message>성공</message>");

        client.syncImagesAndHtml("PRD9", raw, List.of("http://new/rep.jpg", "http://new/img2.jpg"), "<p>hi</p>");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(restClient).put(eq("/rest/prodservices/product/PRD9"), body.capture());
        String sent = body.getValue();
        assertThat(sent).contains("<prdImage01>http://new/rep.jpg</prdImage01>");
        assertThat(sent).contains("<prdImage02>http://new/img2.jpg</prdImage02>");
        // 기존 대표이미지는 남지 않아야 한다.
        assertThat(sent).doesNotContain("http://old/img1.jpg");
        // 라운드트립: 필수 필드는 보존되어야 한다.
        assertThat(sent).contains("<dlvSendCloseTmpltNo>682132</dlvSendCloseTmpltNo>");
        assertThat(sent).contains("<selPrc>10000</selPrc>");
    }

    @Test
    @DisplayName("대표이미지 수정 + 상세HTML 수정 둘 다 호출")
    void callsBothModifyAndDetailHtml() {
        when(restClient.get(anyString())).thenReturn(CURRENT_XML);
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString())).thenReturn("<message>성공</message>");
        when(restClient.post(eq("/rest/prodservices/updateProductDetailCont/PRD9"), contains("prdDescContClob")))
            .thenReturn("<message>성공</message>");

        client.syncImagesAndHtml("PRD9", raw, List.of("http://new/rep.jpg"), "<p>hi</p>");

        verify(restClient).put(eq("/rest/prodservices/product/PRD9"), anyString());
        verify(restClient).post(eq("/rest/prodservices/updateProductDetailCont/PRD9"),
            contains("<![CDATA[<p>hi</p>]]>"));
    }

    @Test
    @DisplayName("hostedImages 비어있음 → 상품수정 PUT 미호출, 상세HTML만 수정")
    void skipsRepresentativeImageWhenNoImages() {
        when(restClient.post(eq("/rest/prodservices/updateProductDetailCont/PRD9"), contains("prdDescContClob")))
            .thenReturn("<message>성공</message>");

        client.syncImagesAndHtml("PRD9", raw, List.of(), "<p>hi</p>");

        verify(restClient, never()).put(eq("/rest/prodservices/product/PRD9"), anyString());
        verify(restClient, never()).get(anyString());
        verify(restClient).post(eq("/rest/prodservices/updateProductDetailCont/PRD9"), anyString());
    }

    @Test
    @DisplayName("상품 전문 조회 실패 → RuntimeException, PUT 미호출")
    void throwsWhenProductInfoFetchFails() {
        when(restClient.get(eq("/rest/prodservices/productinfo/PRD9")))
            .thenReturn("<resultCode>ERROR</resultCode><message>조회실패</message>");

        assertThatThrownBy(() -> client.syncImagesAndHtml("PRD9", raw, List.of("http://new/rep.jpg"), "<p>hi</p>"))
            .isInstanceOf(RuntimeException.class);

        verify(restClient, never()).put(eq("/rest/prodservices/product/PRD9"), anyString());
    }

    @Test
    @DisplayName("상품수정 PUT ERROR 응답 → RuntimeException")
    void throwsWhenModifyFails() {
        when(restClient.get(eq("/rest/prodservices/productinfo/PRD9"))).thenReturn(CURRENT_XML);
        when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
            .thenReturn("<resultCode>ERROR</resultCode><message>수정실패</message>");

        assertThatThrownBy(() -> client.syncImagesAndHtml("PRD9", raw, List.of("http://new/rep.jpg"), "<p>hi</p>"))
            .isInstanceOf(RuntimeException.class);
    }
}
