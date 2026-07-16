package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 완전 상품 삭제(F-PROD-27/28) — CoupangMarketClient.deleteFromMarket 특성화 테스트.
 * 쿠팡 상품 삭제는 seller-products/{sellerProductId} DELETE 경로(발행이 반환·저장하는 식별자)를 호출한다.
 */
@ExtendWith(MockitoExtension.class)
class CoupangMarketClientDeleteTest {

    @Mock private CoupangProperties properties;
    @Mock private ObjectMapper objectMapper;
    @Mock private CoupangRestClient restClient;
    @Mock private CoupangCategoryPredictor categoryPredictor;
    @Mock private CoupangProductParser productParser;
    @Mock private CoupangSearchTagGenerator searchTagGenerator;
    @Mock private CoupangDataMapper dataMapper;
    @Mock private CoupangMetaService metaService;

    private CoupangMarketClient client;

    private static final String SELLER_PRODUCT_ID = "1234567";
    private static final String DELETE_PATH =
        "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + SELLER_PRODUCT_ID;

    @BeforeEach
    void setUp() {
        client = new CoupangMarketClient(properties, objectMapper, restClient, categoryPredictor,
            productParser, searchTagGenerator, dataMapper, metaService);
    }

    @Test
    @DisplayName("정상: seller-products/{sellerProductId} DELETE 경로 호출")
    void deleteCallsSellerProductsDeletePath() {
        client.deleteFromMarket(SELLER_PRODUCT_ID);

        verify(restClient).delete(DELETE_PATH);
    }

    @Test
    @DisplayName("REST 오류(주문이력 하드삭제 거부 등) 시 예외 전파")
    void deletePropagatesRestError() {
        doThrow(new RuntimeException("Coupang API 호출 실패"))
            .when(restClient).delete(DELETE_PATH);

        assertThatThrownBy(() -> client.deleteFromMarket(SELLER_PRODUCT_ID))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("marketItemId 공백이면 삭제 호출 없이 예외")
    void deleteRejectsBlankId() {
        assertThatThrownBy(() -> client.deleteFromMarket("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
