package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 완전 상품 삭제(F-PROD-27/28): SmartstoreMarketClient.deleteFromMarket 는
 * originProductNo 로 원상품 삭제 API(DELETE /v2/products/origin-products/{no})를 호출하고,
 * 실패 시(주문이력 등으로 하드삭제 거부 포함) 예외를 전파한다.
 */
@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientDeleteTest {

    @Mock private SmartstoreRestClient restClient;

    private SmartstoreMarketClient client;

    private static final String ORIGIN_PRODUCT_NO = "OP123";

    @BeforeEach
    void setUp() {
        client = new SmartstoreMarketClient(
            // 신규 등록(publish) 전용 협력자 — 이 테스트가 검증하는 경로에서는 호출되지 않는다.
            null, null, null, null,
            restClient, new ObjectMapper());
    }

    @Test
    @DisplayName("deleteFromMarket → DELETE /v2/products/origin-products/{originProductNo} 호출")
    void deleteCallsOriginProductDeleteEndpoint() {
        client.deleteFromMarket(ORIGIN_PRODUCT_NO);

        verify(restClient).delete("/v2/products/origin-products/" + ORIGIN_PRODUCT_NO);
    }

    @Test
    @DisplayName("삭제 API 오류(주문이력 등 하드삭제 거부) 시 예외 전파")
    void deletePropagatesExceptionOnFailure() {
        doThrow(new RuntimeException("Smartstore API 호출 실패"))
            .when(restClient).delete("/v2/products/origin-products/" + ORIGIN_PRODUCT_NO);

        assertThatThrownBy(() -> client.deleteFromMarket(ORIGIN_PRODUCT_NO))
            .isInstanceOf(RuntimeException.class);
    }
}
