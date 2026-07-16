package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-PROD-27/28: ElevenstMarketClient.deleteFromMarket — 11번가 상품 삭제 API 호출 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientDeleteTest {

    @Mock private ElevenstMarketRestClient restClient;

    private ElevenstMarketClient client;

    @BeforeEach
    void setUp() {
        client = new ElevenstMarketClient(restClient);
    }

    @Test
    @DisplayName("성공 응답 → DELETE /rest/prodservices/product/{prdNo} 호출")
    void deleteCallsDeleteEndpointWithPrdNo() {
        when(restClient.delete("/rest/prodservices/product/E999"))
            .thenReturn("<Product><resultCode>200</resultCode><message>정상적으로 처리되었습니다.</message></Product>");

        client.deleteFromMarket("E999");

        verify(restClient).delete("/rest/prodservices/product/E999");
    }

    @Test
    @DisplayName("marketItemId(elevenstId=prdNo)를 그대로 삭제 경로에 사용")
    void deleteUsesMarketItemIdAsPrdNo() {
        when(restClient.delete(anyString())).thenReturn("<message>성공</message>");

        client.deleteFromMarket("PRD12345");

        verify(restClient).delete("/rest/prodservices/product/PRD12345");
    }

    @Test
    @DisplayName("ERROR 응답 → RuntimeException 전파 (주문이력 등 삭제 거부)")
    void errorResponseThrowsRuntimeException() {
        when(restClient.delete(anyString()))
            .thenReturn("<resultCode>500</resultCode><message>주문 이력이 있어 삭제할 수 없습니다.</message>");

        assertThatThrownBy(() -> client.deleteFromMarket("E999"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("resultCode ERROR 응답 → RuntimeException 전파")
    void resultCodeErrorThrowsRuntimeException() {
        when(restClient.delete(anyString()))
            .thenReturn("<resultCode>ERROR</resultCode><message>NO_RESPONSE</message>");

        assertThatThrownBy(() -> client.deleteFromMarket("E999"))
            .isInstanceOf(RuntimeException.class);
    }
}
