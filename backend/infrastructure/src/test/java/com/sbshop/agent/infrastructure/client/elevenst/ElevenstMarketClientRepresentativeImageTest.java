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

/**
 * D-092: 11번가 대표이미지/상세HTML 재게시 테스트(buying-agent 라운드트립 이식).
 * 신규상품조회(/rest/prodmarketservice/prodmarket/{prdNo})로 전체 전문 GET → 이미지/HTML 치환 +
 * 필수 승격 필드(택배사·원재료·원산지·배송/반품·출고지 주소코드) 주입 → 상품수정 PUT.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientRepresentativeImageTest {

    @Mock private ElevenstMarketRestClient restClient;

    private ElevenstMarketClient client;
    private Map<String, Object> raw;

    // 신규상품조회 GET이 돌려주는 현재 상품 전문(대표이미지·상세·기존 주소코드 포함).
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
        // 판매자 실계정 주소코드로 교체(기존 99/88 제거)
        assertThat(sent).contains("<addrSeqOut>5</addrSeqOut>");
        assertThat(sent).contains("<addrSeqIn>3</addrSeqIn>");
        assertThat(sent).doesNotContain("<addrSeqOut>99</addrSeqOut>");
        // 조회 메타태그 제거(파서 에러 방지)
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
