package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 완전 상품 삭제(F-PROD-27/28): Cafe24MarketClient.deleteFromMarket 특성화 테스트.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24MarketClientDeleteTest {

    @Mock private Cafe24RestClient cafe24RestClient;
    @Mock private HtmlImageExtractor imageExtractor;
    @Mock private Cafe24CategoryResolver categoryResolver;

    private Cafe24MarketClient client;

    private static final String PRODUCT_NO = "12345";

    @BeforeEach
    void setUp() {
        client = new Cafe24MarketClient(new ObjectMapper(), cafe24RestClient, imageExtractor, categoryResolver);
    }

    @Test
    @DisplayName("deleteFromMarket → DELETE /admin/products/{product_no} 호출")
    void deleteFromMarketCallsCorrectPath() {
        client.deleteFromMarket(PRODUCT_NO);

        verify(cafe24RestClient).delete("/admin/products/" + PRODUCT_NO);
    }

    @Test
    @DisplayName("Cafe24 삭제 API 오류 시 예외 전파")
    void deleteFromMarketPropagatesError() {
        doThrow(new RuntimeException("Cafe24 API DELETE 호출 실패"))
            .when(cafe24RestClient).delete("/admin/products/" + PRODUCT_NO);

        assertThatThrownBy(() -> client.deleteFromMarket(PRODUCT_NO))
            .isInstanceOf(RuntimeException.class);
    }
}
